package com.channelweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The BFF against a fake gateway (WireMock: the token endpoint, otp-service and the platform), a real
 * Postgres and a real Redis session store. Checks what the BFF sends upstream as much as what it answers.
 */
@SpringBootTest(properties = {
        "app.channel.client-id=channel-web-test",
        "spring.security.oauth2.client.registration.gateway.client-id=channel-web-test"})
@AutoConfigureMockMvc
@Testcontainers
class ChannelWebIT {

    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final WireMockServer GW = new WireMockServer(wireMockConfig().dynamicPort());
    static final ECKey OTP_KEY;

    static {
        GW.start();
        try {
            OTP_KEY = new ECKeyGenerator(Curve.P_256).keyID("otp-test-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.channel.gateway-url", GW::baseUrl);
        r.add("spring.security.oauth2.client.provider.cards.token-uri", () -> GW.baseUrl() + "/token");
    }

    @AfterAll
    static void stop() {
        GW.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;

    /** A browser: keeps cookies between calls. */
    final Map<String, Cookie> jar = new HashMap<>();
    String mobile;

    @BeforeEach
    void reset() {
        GW.resetAll();
        jar.clear();
        mobile = "98" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        GW.stubFor(WireMock.post("/token").willReturn(okJson("{\"access_token\":\"gw-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        GW.stubFor(WireMock.get("/otp/v1/jwks.json").willReturn(okJson(new JWKSet(OTP_KEY.toPublicJWK()).toString())));
        GW.stubFor(WireMock.post("/otp/v1/challenges").willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"expiresAt\":\"2026-11-04T10:10:00Z\","
                        + "\"resendAvailableAt\":\"2026-11-04T10:00:30Z\",\"codeLength\":6}")));
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        jar.values().forEach(request::cookie);
        MvcResult result = mvc.perform(request).andReturn();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getMaxAge() == 0) jar.remove(c.getName()); else jar.put(c.getName(), c);
        }
        return result;
    }

    private MvcResult postJson(String path, Object body) throws Exception {
        return call(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String proof(String audience, String subject, String purpose, String cid, String bnd) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("otp-test-1")
                .type(new JOSEObjectType("otp-proof+jwt")).build(),
                new JWTClaimsSet.Builder().issuer("urn:sim-comms:otp-service:test").audience(audience).subject(subject)
                        .claim("pur", purpose).claim("cid", cid).claim("bnd", bnd).jwtID(UUID.randomUUID().toString())
                        .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000)).build());
        jwt.sign(new ECDSASigner(OTP_KEY));
        return jwt.serialize();
    }

    private JsonNode lastSend() throws Exception {
        List<LoggedRequest> sends = GW.findAll(postRequestedFor(urlEqualTo("/otp/v1/challenges")));
        return json.readTree(sends.get(sends.size() - 1).getBodyAsString());
    }

    private void stubVerify(String audience, String purpose) throws Exception {
        JsonNode sent = lastSend();
        String p = proof(audience, sent.get("subjectRef").asText(), purpose, "11111111-1111-1111-1111-111111111111",
                sent.get("bindingHash").asText());
        GW.stubFor(WireMock.post("/otp/v1/challenges/11111111-1111-1111-1111-111111111111/verification")
                .willReturn(okJson("{\"result\":\"VERIFIED\",\"proof\":\"" + p + "\"}")));
    }

    private void signIn() throws Exception {
        assertThat(postJson("/bff/auth/otp/send", Map.of("mobile", mobile)).getResponse().getStatus()).isEqualTo(202);
        stubVerify("channel-web-test", "SIGN_IN");
        assertThat(postJson("/bff/auth/otp/verify", Map.of("code", "123456")).getResponse().getStatus()).isEqualTo(200);
    }

    private int version() throws Exception {
        return body(call(get("/bff/drafts/current"))).get("rowVersion").asInt();
    }

    private MvcResult section(String name, Map<String, Object> fields, int ifMatch) throws Exception {
        return call(put("/bff/drafts/current/sections/" + name).with(csrf()).header("If-Match", String.valueOf(ifMatch))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fields)));
    }

    private void completeDraft(String pan) throws Exception {
        assertThat(postJson("/bff/drafts", Map.of("noticeVersion", "PN-2026.1", "locale", "en-IN",
                "purposes", Map.of("PROCESSING", true, "BUREAU_CHECK", true, "MARKETING", false))).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(section("about-you", Map.of("fullName", "Asha Verma", "pan", pan, "dateOfBirth", "1990-04-12",
                "email", "asha@example.com"), version()).getResponse().getStatus()).isEqualTo(200);
        assertThat(section("address", Map.of("addressLine1", "12 Residency Road", "city", "Bengaluru", "state", "Karnataka",
                "pincode", "560025"), version()).getResponse().getStatus()).isEqualTo(200);
        assertThat(section("work", Map.of("employmentType", "SALARIED", "employerName", "Acme Logistics",
                "monthlyIncome", "120000", "monthlyObligation", "25000"), version()).getResponse().getStatus()).isEqualTo(200);
        assertThat(section("card", Map.of("cardType", "GOLD", "kfsAcknowledged", true), version()).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void signInNeedsTheRightCodeAndAValidProofThenRotatesTheSession() throws Exception {
        assertThat(call(get("/bff/session")).getResponse().getContentAsString()).contains("\"authenticated\":false");
        MvcResult sent = postJson("/bff/auth/otp/send", Map.of("mobile", "+91 " + mobile));
        assertThat(sent.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(sent).get("maskedMobile").asText()).isEqualTo("XXXXXX" + mobile.substring(6));
        String before = jar.get("WEB_SESSION").getValue();

        JsonNode upstream = lastSend();
        assertThat(upstream.get("msisdn").asText()).isEqualTo("+91" + mobile);
        assertThat(upstream.get("subjectRef").asText()).matches("[A-Za-z0-9_-]{43}");
        LoggedRequest request = GW.findAll(postRequestedFor(urlEqualTo("/otp/v1/challenges"))).get(0);
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gw-token");
        assertThat(request.getHeader("Host")).as("the gateway routes by Host").isEqualTo("api.dev.localtest.me");
        assertThat(request.getHeader("x-customer-key")).isEqualTo(upstream.get("subjectRef").asText());

        GW.stubFor(WireMock.post("/otp/v1/challenges/11111111-1111-1111-1111-111111111111/verification").willReturn(aResponse()
                .withStatus(422).withHeader("Content-Type", "application/json")
                .withBody("{\"errorCode\":\"OTP_INCORRECT\",\"attemptsRemaining\":2}")));
        MvcResult wrong = postJson("/bff/auth/otp/verify", Map.of("code", "000000"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(wrong).get("attemptsRemaining").asInt()).isEqualTo(2);

        stubVerify("channel-web-test", "SIGN_IN");
        assertThat(postJson("/bff/auth/otp/verify", Map.of("code", "123456")).getResponse().getStatus()).isEqualTo(200);
        assertThat(jar.get("WEB_SESSION").getValue()).as("new session id at sign-in").isNotEqualTo(before);
        assertThat(jar.get("WEB_SESSION").isHttpOnly()).isTrue();
        assertThat(call(get("/bff/session")).getResponse().getContentAsString()).contains("\"authenticated\":true");
    }

    @Test
    void aProofForAnotherAudienceDoesNotSignTheCustomerIn() throws Exception {
        postJson("/bff/auth/otp/send", Map.of("mobile", mobile));
        stubVerify("channel-branch-test", "SIGN_IN");
        assertThat(postJson("/bff/auth/otp/verify", Map.of("code", "123456")).getResponse().getStatus()).isEqualTo(502);
        assertThat(call(get("/bff/applications")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void nothingIsStoredBeforeTheRequiredConsents() throws Exception {
        signIn();
        MvcResult refused = postJson("/bff/drafts", Map.of("noticeVersion", "PN-2026.1", "locale", "en-IN",
                "purposes", Map.of("PROCESSING", true, "BUREAU_CHECK", false)));
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(refused).get("errorCode").asText()).isEqualTo("CONSENT_REQUIRED");
        assertThat(call(get("/bff/drafts/current")).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aFullApplicationIsSubmittedThroughTheGatewayAndOnlyItsOwnerSeesIt() throws Exception {
        signIn();
        String pan = "ABCPK" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)) + "Q";
        completeDraft(pan);
        assertThat(section("card", Map.of("cardType", "GOLD", "kfsAcknowledged", true), 0).getResponse().getStatus())
                .as("a stale version").isEqualTo(412);
        byte[] stored = db.sql("SELECT answers FROM draft").query(byte[].class).list().get(0);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(pan).doesNotContain("Asha");

        UUID id = UUID.randomUUID();
        GW.stubFor(WireMock.post("/api/v1/applications").willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("{\"applicationId\":\"" + id + "\",\"applicationRef\":\"CC-2026-1104-ABCDEF\",\"status\":\"RECEIVED\"}")));
        MvcResult submitted = call(post("/bff/drafts/current/submission").with(csrf()));
        assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(submitted).get("applicationRef").asText()).isEqualTo("CC-2026-1104-ABCDEF");

        LoggedRequest platform = GW.findAll(postRequestedFor(urlEqualTo("/api/v1/applications"))).get(0);
        assertThat(platform.getHeader("X-Channel-ID")).isEqualTo("WEB");
        assertThat(platform.getHeader("Idempotency-Key")).startsWith("cw-");
        assertThat(platform.getHeader("x-customer-key")).matches("[A-Za-z0-9_-]{43}");
        assertThat(platform.getHeader("Cookie")).isNull();
        JsonNode sentBody = json.readTree(platform.getBodyAsString());
        assertThat(sentBody.at("/applicant/phone").asText()).isEqualTo("+91" + mobile);
        assertThat(sentBody.at("/applicant/pan").asText()).isEqualTo(pan);
        assertThat(sentBody.get("consentBureauPull").asBoolean()).isTrue();
        assertThat(sentBody.get("consentMITC").asBoolean()).isTrue();
        assertThat(sentBody.get("cardType").asText()).isEqualTo("GOLD");
        assertThat(call(get("/bff/drafts/current")).getResponse().getStatus()).as("the draft is gone").isEqualTo(404);

        GW.stubFor(WireMock.get("/api/v1/applications/" + id + "/next-action").willReturn(okJson("{\"applicationId\":\"" + id
                + "\",\"customerStatus\":\"UNDER_REVIEW\",\"nextAction\":{\"type\":\"NONE\"},\"canWithdraw\":true,"
                + "\"updatedAt\":\"2026-11-04T10:00:05Z\"}")));
        MvcResult status = call(get("/bff/applications/" + id + "/status"));
        assertThat(body(status).get("customerStatus").asText()).isEqualTo("UNDER_REVIEW");
        assertThat(body(status).has("decision")).isFalse();
        assertThat(body(call(get("/bff/applications"))).get("applications").get(0).get("applicationRef").asText())
                .isEqualTo("CC-2026-1104-ABCDEF");

        // Another customer: not found, and no upstream call is made.
        jar.clear();
        mobile = "97" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        signIn();
        int before = GW.findAll(getRequestedFor(urlPathMatching("/api/v1/applications/.*"))).size();
        assertThat(call(get("/bff/applications/" + id + "/status")).getResponse().getStatus()).isEqualTo(404);
        assertThat(GW.findAll(getRequestedFor(urlPathMatching("/api/v1/applications/.*")))).hasSize(before);
    }

    @Test
    void aGatewayFailureKeepsTheIdempotencyKeyForTheRetry() throws Exception {
        signIn();
        completeDraft("ABCPR" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)) + "Q");
        GW.stubFor(WireMock.post("/api/v1/applications").willReturn(aResponse().withStatus(503).withHeader("Retry-After", "7")));
        MvcResult failed = call(post("/bff/drafts/current/submission").with(csrf()));
        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        assertThat(failed.getResponse().getHeader("Retry-After")).isEqualTo("7");

        GW.stubFor(WireMock.post("/api/v1/applications").willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("{\"applicationId\":\"" + UUID.randomUUID() + "\",\"applicationRef\":\"CC-2026-1104-RETRY1\"}")));
        assertThat(call(post("/bff/drafts/current/submission").with(csrf())).getResponse().getStatus()).isEqualTo(202);
        List<String> keys = new ArrayList<>();
        GW.findAll(postRequestedFor(urlEqualTo("/api/v1/applications"))).forEach(r -> keys.add(r.getHeader("Idempotency-Key")));
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isEqualTo(keys.get(0));
    }

    @Test
    void badAnswersComeBackAsFieldErrors() throws Exception {
        signIn();
        postJson("/bff/drafts", Map.of("noticeVersion", "PN-2026.1", "locale", "en-IN",
                "purposes", Map.of("PROCESSING", true, "BUREAU_CHECK", true)));
        MvcResult bad = section("about-you", Map.of("fullName", "A", "pan", "123", "dateOfBirth", "2015-01-01"), version());
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("\"field\":\"pan\",\"code\":\"format\"")
                .contains("\"field\":\"dateOfBirth\",\"code\":\"tooYoung\"").contains("\"field\":\"email\",\"code\":\"required\"");
    }

    @Test
    void withdrawalNeedsAFreshStepUpAndWritesNeedTheCsrfToken() throws Exception {
        assertThat(call(get("/bff/applications")).getResponse().getStatus()).isEqualTo(401);
        signIn();
        assertThat(call(post("/bff/drafts").contentType(MediaType.APPLICATION_JSON).content("{}")).getResponse().getStatus())
                .as("no CSRF token").isEqualTo(403);
        UUID id = UUID.randomUUID();
        GW.stubFor(WireMock.post("/api/v1/applications").willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("{\"applicationId\":\"" + id + "\",\"applicationRef\":\"CC-2026-1104-WDRAW1\"}")));
        completeDraft("ABCPW" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)) + "Q");
        call(post("/bff/drafts/current/submission").with(csrf()));

        MvcResult refused = call(post("/bff/applications/" + id + "/withdrawal").with(csrf()));
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused).get("errorCode").asText()).isEqualTo("STEP_UP_REQUIRED");

        assertThat(call(post("/bff/auth/step-up/send").with(csrf())).getResponse().getStatus()).isEqualTo(202);
        stubVerify("channel-web-test", "STEP_UP");
        assertThat(postJson("/bff/auth/step-up/verify", Map.of("code", "123456")).getResponse().getStatus()).isEqualTo(200);
        GW.stubFor(WireMock.post("/api/v1/applications/" + id + "/withdrawal").willReturn(okJson("{\"status\":\"WITHDRAWN\"}")));
        GW.stubFor(WireMock.get("/api/v1/applications/" + id + "/next-action").willReturn(okJson(
                "{\"customerStatus\":\"WITHDRAWN\",\"nextAction\":{\"type\":\"NONE\"},\"canWithdraw\":false}")));
        MvcResult done = call(post("/bff/applications/" + id + "/withdrawal").with(csrf()));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(done).get("customerStatus").asText()).isEqualTo("WITHDRAWN");
    }
}
