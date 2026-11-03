package com.channelweb.domain;

import com.channelweb.config.ChannelProperties;
import com.channelweb.upstream.GatewayClient;
import com.channelweb.upstream.UpstreamException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The customer's draft application in the channel's own database: created only after the consents
 * (R3), saved section by section with optimistic locking, purged after the retention period, and
 * submitted through the gateway with an idempotency key stored before the call.
 */
@Service
public class Drafts {

    private static final Logger log = LoggerFactory.getLogger(Drafts.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    public record Consents(boolean processing, boolean bureauCheck, boolean marketing) { }

    public record Draft(UUID id, Map<String, Object> answers, Set<String> sectionsDone, int rowVersion,
                        Consents consents, Instant updatedAt, Instant expiresAt) {
        public boolean complete() {
            return sectionsDone.containsAll(Sections.ORDER);
        }
    }

    public record Submitted(UUID applicationId, String applicationRef) { }

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final AnswerCipher cipher;
    private final ObjectMapper json;
    private final GatewayClient gateway;
    private final ChannelProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public Drafts(JdbcClient db, TransactionTemplate tx, AnswerCipher cipher, ObjectMapper json, GatewayClient gateway,
                  ChannelProperties properties, MeterRegistry meters, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.cipher = cipher;
        this.json = json;
        this.gateway = gateway;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** Consent first: processing and the bureau check are required to continue; marketing is optional, default No. */
    public Draft start(String customerKey, String mobile, Consents consents, String locale) {
        if (!consents.processing() || !consents.bureauCheck()) {
            throw new ChannelException(422, "CONSENT_REQUIRED");
        }
        Optional<Draft> existing = find(customerKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            db.sql("""
                    INSERT INTO draft (id, customer_key, answers, notice_version, locale, consent_processing,
                                       consent_bureau, consent_marketing, created_at, updated_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, customerKey, encrypt(id, Map.of("mobile", mobile)), properties.noticeVersion(), locale,
                            true, true, consents.marketing(), ts(now), ts(now), ts(now.plus(properties.draftRetention())))
                    .update();
            db.sql("""
                    INSERT INTO consent_record (id, customer_key, notice_version, locale, processing, bureau_check,
                                                marketing, given_at) VALUES (?, ?, ?, ?, true, true, ?, ?)""")
                    .params(UUID.randomUUID(), customerKey, properties.noticeVersion(), locale, consents.marketing(), ts(now))
                    .update();
        });
        meters.counter("cw.draft.started").increment();
        log.info("Draft {} started", id);
        return find(customerKey).orElseThrow();
    }

    public Optional<Draft> find(String customerKey) {
        return db.sql("""
                SELECT id, answers, sections_done, row_version, consent_processing, consent_bureau, consent_marketing,
                       updated_at, expires_at FROM draft WHERE customer_key = ?""")
                .param(customerKey)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Draft(id, decrypt(id, rs.getBytes("answers")), split(rs.getString("sections_done")),
                            rs.getInt("row_version"), new Consents(rs.getBoolean("consent_processing"),
                            rs.getBoolean("consent_bureau"), rs.getBoolean("consent_marketing")),
                            rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
                })
                .optional();
    }

    /** Saves one section if the caller saw the latest version; otherwise 412 (the draft changed elsewhere). */
    public Draft save(String customerKey, String section, Map<String, Object> fields, int ifMatch) {
        Draft draft = find(customerKey).orElseThrow(() -> new ChannelException(404, "NO_DRAFT"));
        Sections.Result result = Sections.validate(section, fields, LocalDate.now(clock.withZone(INDIA)));
        if (!result.ok()) {
            throw new ChannelException(400, "VALIDATION_FAILED", result.errors(), null, null);
        }
        Map<String, Object> answers = new LinkedHashMap<>(draft.answers());
        answers.put(section, result.values());
        Set<String> done = new LinkedHashSet<>(draft.sectionsDone());
        done.add(section);
        Instant now = clock.instant();
        int updated = db.sql("""
                UPDATE draft SET answers = ?, sections_done = ?, row_version = row_version + 1, updated_at = ?,
                                 expires_at = ? WHERE id = ? AND row_version = ?""")
                .params(encrypt(draft.id(), answers), String.join(",", done), ts(now),
                        ts(now.plus(properties.draftRetention())), draft.id(), ifMatch)
                .update();
        if (updated == 0) {
            throw new ChannelException(412, "DRAFT_CHANGED");
        }
        return find(customerKey).orElseThrow();
    }

    public void discard(String customerKey) {
        db.sql("DELETE FROM draft WHERE customer_key = ?").param(customerKey).update();
    }

    /**
     * Submits through the gateway. The idempotency key is stored before the call, so a retry after a
     * timeout or a crash sends the same key and the platform answers with the same application.
     */
    public Submitted submit(String customerKey) {
        Draft draft = find(customerKey).orElseThrow(() -> new ChannelException(404, "NO_DRAFT"));
        if (!draft.complete()) {
            throw new ChannelException(422, "DRAFT_INCOMPLETE");
        }
        String key = submissionKey(draft.id());
        if (key == null) {
            key = "cw-" + UUID.randomUUID();
            db.sql("UPDATE draft SET submission_key = ? WHERE id = ? AND submission_key IS NULL")
                    .params(key, draft.id()).update();
            key = submissionKey(draft.id());
        }
        JsonNode accepted;
        try {
            accepted = gateway.submit(customerKey, key, platformBody(draft));
        } catch (UpstreamException e) {
            meters.counter("cw.submission.refused", "code", e.errorCode()).increment();
            if (e.transientFailure()) {
                throw new ChannelException(503, "TRY_AGAIN", List.of(), e.retryAfterSeconds() != null ? e.retryAfterSeconds() : 30L, null);
            }
            if ("DUPLICATE_APPLICANT".equals(e.errorCode())) {
                throw new ChannelException(409, "ALREADY_IN_PROGRESS");
            }
            log.warn("Submission of draft {} refused: {}", draft.id(), e.errorCode());
            throw new ChannelException(422, "SUBMISSION_REFUSED");
        }
        UUID applicationId = UUID.fromString(accepted.path("applicationId").asText());
        String ref = accepted.path("applicationRef").asText();
        String product = String.valueOf(((Map<?, ?>) draft.answers().get("card")).get("cardType"));
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            db.sql("""
                    INSERT INTO customer_application (application_id, customer_key, application_ref, product, created_at)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (application_id) DO NOTHING""")
                    .params(applicationId, customerKey, ref, product, ts(now)).update();
            db.sql("UPDATE consent_record SET application_id = ? WHERE customer_key = ? AND application_id IS NULL")
                    .params(applicationId, customerKey).update();
            db.sql("DELETE FROM draft WHERE id = ?").param(draft.id()).update();
        });
        meters.counter("cw.submission.accepted").increment();
        log.info("Draft {} submitted as application {}", draft.id(), applicationId);
        return new Submitted(applicationId, ref);
    }

    private String submissionKey(UUID draftId) {
        return db.sql("SELECT submission_key FROM draft WHERE id = ?").param(draftId)
                .query((rs, n) -> java.util.Optional.ofNullable(rs.getString(1))).single().orElse(null);
    }

    /** The platform's v1 body, built here: the browser never sends it. */
    Map<String, Object> platformBody(Draft d) {
        Map<?, ?> you = (Map<?, ?>) d.answers().get("about-you");
        Map<?, ?> address = (Map<?, ?>) d.answers().get("address");
        Map<?, ?> work = (Map<?, ?>) d.answers().get("work");
        Map<?, ?> card = (Map<?, ?>) d.answers().get("card");
        Map<String, Object> applicant = new LinkedHashMap<>();
        applicant.put("fullName", you.get("fullName"));
        applicant.put("pan", you.get("pan"));
        applicant.put("dateOfBirth", you.get("dateOfBirth"));
        applicant.put("phone", d.answers().get("mobile"));
        applicant.put("email", you.get("email"));
        applicant.put("addressLine1", address.get("addressLine1"));
        if (address.get("addressLine2") != null) applicant.put("addressLine2", address.get("addressLine2"));
        applicant.put("city", address.get("city"));
        applicant.put("state", address.get("state"));
        applicant.put("pincode", address.get("pincode"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicant", applicant);
        body.put("cardType", card.get("cardType"));
        body.put("monthlyIncome", work.get("monthlyIncome"));
        body.put("monthlyObligation", work.get("monthlyObligation"));
        if (work.get("employerName") != null) body.put("employerName", work.get("employerName"));
        body.put("employmentType", work.get("employmentType"));
        // DEC-11 web slice: the three v1 booleans until the consent ledger (W10.02).
        body.put("consentDataProcessing", d.consents().processing());
        body.put("consentBureauPull", d.consents().bureauCheck());
        body.put("consentMITC", Boolean.TRUE.equals(card.get("kfsAcknowledged")));
        body.put("mitcVersion", properties.mitcVersion());
        // Tier A: the channel's own KYC and fraud screening references (simulated); W11.01 replaces them.
        body.put("kycReferenceId", UUID.nameUUIDFromBytes(("kyc:" + d.id()).getBytes()).toString());
        body.put("fraudScreenId", UUID.nameUUIDFromBytes(("fraud:" + d.id()).getBytes()).toString());
        body.put("attribution", Map.of("source", "web", "medium", "direct"));
        return body;
    }

    /** DPDP storage limitation: drafts left alone past the retention period are deleted. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgeExpired() {
        int purged = db.sql("DELETE FROM draft WHERE expires_at < ?").param(ts(clock.instant())).update();
        if (purged > 0) {
            meters.counter("cw.draft.purged").increment(purged);
            log.info("Purged {} expired drafts", purged);
        }
    }

    private byte[] encrypt(UUID id, Map<String, Object> answers) {
        try {
            return cipher.encrypt(json.writeValueAsString(answers), id.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Draft could not be encrypted", e);
        }
    }

    private Map<String, Object> decrypt(UUID id, byte[] stored) {
        try {
            return json.readValue(cipher.decrypt(stored, id.toString()), MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Draft could not be read", e);
        }
    }

    private static Set<String> split(String csv) {
        return csv == null || csv.isBlank() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
