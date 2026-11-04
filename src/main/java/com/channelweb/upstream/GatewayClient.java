package com.channelweb.upstream;

import com.channelweb.config.ChannelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every upstream call goes through the API gateway (ADR-0019): the platform's channel routes and
 * otp-service's comms routes. Each request is built here from scratch (no browser header is ever
 * forwarded) with this channel's client-credentials token, the customer's opaque key, a correlation
 * id and, for writes, an idempotency key.
 */
@Component
public class GatewayClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final ChannelProperties properties;

    public GatewayClient(RestClient.Builder builder, OAuth2AuthorizedClientManager clients, ObjectMapper json,
                         ChannelProperties properties) {
        OAuth2ClientHttpRequestInterceptor tokens = new OAuth2ClientHttpRequestInterceptor(clients);
        tokens.setClientRegistrationIdResolver(request -> "gateway");
        // The JDK's HTTP clients drop a Host header; the gateway routes by it (one listener per environment).
        // No automatic retries: only the BFF retries, and a write only with its stored idempotency key.
        HttpComponentsClientHttpRequestFactory timeouts = new HttpComponentsClientHttpRequestFactory(
                org.apache.hc.client5.http.impl.classic.HttpClients.custom().disableAutomaticRetries()
                        .disableRedirectHandling().build());
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.baseUrl(properties.gatewayUrl()).requestFactory(timeouts).requestInterceptor(tokens)
                .defaultHeader(HttpHeaders.HOST, properties.apiHost()).build();
        this.json = json;
        this.properties = properties;
    }

    // ── otp-service (route class comms) ─────────────────────────────────────────────

    public JsonNode sendOtp(String customerKey, String idempotencyKey, String purpose, String msisdn, String bindingHash) {
        return call("otp-send", () -> http.post().uri("/otp/v1/challenges").headers(h -> common(h, customerKey))
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("purpose", purpose, "msisdn", msisdn, "subjectRef", customerKey, "bindingHash", bindingHash))
                .retrieve().body(JsonNode.class));
    }

    public JsonNode resendOtp(String customerKey, String challengeId, String msisdn, String bindingHash) {
        return call("otp-resend", () -> http.post().uri("/otp/v1/challenges/{id}/resend", challengeId)
                .headers(h -> common(h, customerKey)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("msisdn", msisdn, "bindingHash", bindingHash)).retrieve().body(JsonNode.class));
    }

    /** @return the signed proof */
    public String verifyOtp(String customerKey, String challengeId, String code, String bindingHash) {
        JsonNode body = call("otp-verify", () -> http.post().uri("/otp/v1/challenges/{id}/verification", challengeId)
                .headers(h -> common(h, customerKey)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "bindingHash", bindingHash)).retrieve().body(JsonNode.class));
        return body.path("proof").asText();
    }

    public String otpKeys() {
        return call("otp-jwks", () -> http.get().uri("/otp/v1/jwks.json")
                .header("X-Correlation-ID", correlationId()).retrieve().body(String.class));
    }

    // ── platform (route class channel) ──────────────────────────────────────────────

    public JsonNode submit(String customerKey, String idempotencyKey, Map<String, Object> application) {
        return call("submit", () -> http.post().uri("/api/v1/applications").headers(h -> common(h, customerKey))
                .header("Idempotency-Key", idempotencyKey).header("X-Channel-ID", properties.channelId())
                .contentType(MediaType.APPLICATION_JSON).body(application).retrieve().body(JsonNode.class));
    }

    public JsonNode nextAction(String customerKey, UUID applicationId) {
        return call("next-action", () -> http.get().uri("/api/v1/applications/{id}/next-action", applicationId)
                .headers(h -> common(h, customerKey)).retrieve().body(JsonNode.class));
    }

    public JsonNode withdraw(String customerKey, UUID applicationId, String idempotencyKey) {
        return call("withdraw", () -> http.post().uri("/api/v1/applications/{id}/withdrawal", applicationId)
                .headers(h -> common(h, customerKey)).header("Idempotency-Key", idempotencyKey)
                .header("X-Channel-ID", properties.channelId()).retrieve().body(JsonNode.class));
    }

    /** The customer's answer to their offer (W10.04): the exact offer, and the verification behind it. */
    public JsonNode decideOffer(String customerKey, UUID applicationId, String idempotencyKey, UUID offerId,
                                String termsHash, String decision, String verificationRef) {
        Map<String, Object> answer = Map.of("offerId", offerId.toString(), "termsHash", termsHash,
                "decision", decision, "verificationRef", verificationRef);
        return call("offer-acceptance", () -> http.post().uri("/api/v1/applications/{id}/offer-acceptance", applicationId)
                .headers(h -> common(h, customerKey)).header("Idempotency-Key", idempotencyKey)
                .header("X-Channel-ID", properties.channelId())
                .contentType(MediaType.APPLICATION_JSON).body(answer).retrieve().body(JsonNode.class));
    }

    public JsonNode requestErasure(String customerKey, UUID applicationId, String idempotencyKey) {
        return call("erasure", () -> http.post().uri("/api/v1/applications/{id}/erasure-requests", applicationId)
                .headers(h -> common(h, customerKey)).header("Idempotency-Key", idempotencyKey)
                .header("X-Channel-ID", properties.channelId()).retrieve().body(JsonNode.class));
    }

    private void common(HttpHeaders h, String customerKey) {
        h.set("x-customer-key", customerKey);
        h.set("X-Correlation-ID", correlationId());
        h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
    }

    private static String correlationId() {
        String id = MDC.get("correlationId");
        return id != null ? id : "cw-" + UUID.randomUUID();
    }

    /** Runs the call; maps an error response or a network failure to {@link UpstreamException}. */
    private <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            String code = "UPSTREAM_" + e.getStatusCode().value();
            Integer remaining = null;
            try {
                JsonNode body = json.readTree(e.getResponseBodyAsByteArray());
                if (body.hasNonNull("errorCode")) {
                    code = body.get("errorCode").asText();
                }
                if (body.hasNonNull("attemptsRemaining")) {
                    remaining = body.get("attemptsRemaining").asInt();
                }
            } catch (Exception ignored) {
                // not JSON (a gateway 401 or 404): the status says enough
            }
            Long retryAfter = null;
            String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (header != null && header.matches("[0-9]{1,6}")) {
                retryAfter = Long.parseLong(header);
            }
            log.info("Upstream {} refused: status={} code={}", operation, e.getStatusCode().value(), code);
            throw new UpstreamException(e.getStatusCode().value(), code, retryAfter, remaining);
        } catch (ResourceAccessException e) {
            log.warn("Upstream {} unreachable: {}", operation, e.getClass().getSimpleName());
            throw new UpstreamException(0, "UPSTREAM_UNAVAILABLE", null, null);
        }
    }
}
