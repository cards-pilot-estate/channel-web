package com.channelweb.api;

import com.channelweb.config.ChannelProperties;
import com.channelweb.domain.ChannelException;
import com.channelweb.domain.Drafts;
import com.channelweb.security.CustomerSession;
import com.channelweb.upstream.GatewayClient;
import com.channelweb.upstream.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The customer's applications. The BFF serves an application only to the customer who submitted it
 * (its own map, before any upstream call); the platform serves it only to this channel client.
 * Statuses are the platform's customer vocabulary (next-action); the channel holds no mapping.
 */
@RestController
public class ApplicationsController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationsController.class);
    private static final Set<String> KNOWN = Set.of("SUBMITTED", "IN_PROGRESS", "UNDER_REVIEW", "OFFER_READY",
            "APPROVED", "DECLINED", "WITHDRAWN", "CLOSED");
    private static final Set<String> ANSWERS = Set.of("ACCEPT", "DECLINE");
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    /** The customer's answer to their offer: which offer they saw and what they chose. */
    public record OfferAnswer(String decision, String offerId, String termsHash) { }

    private record Owned(UUID applicationId, String applicationRef, String product, Instant createdAt) { }

    private final JdbcClient db;
    private final GatewayClient gateway;
    private final Drafts drafts;
    private final ChannelProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public ApplicationsController(JdbcClient db, GatewayClient gateway, Drafts drafts, ChannelProperties properties,
                                  MeterRegistry meters, Clock clock) {
        this.db = db;
        this.gateway = gateway;
        this.drafts = drafts;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    @GetMapping("/bff/applications")
    public Map<String, Object> list() {
        String customer = CustomerSession.customerKey();
        List<Map<String, Object>> applications = new ArrayList<>();
        for (Owned o : owned(customer)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationId", o.applicationId());
            row.put("applicationRef", o.applicationRef());
            row.put("product", o.product());
            row.put("submittedAt", o.createdAt());
            try {
                row.putAll(status(customer, o));
            } catch (ChannelException e) {
                row.put("customerStatus", "UNKNOWN");
            }
            applications.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        drafts.find(customer).ifPresent(d -> body.put("draft", Map.of("sectionsDone", d.sectionsDone(),
                "updatedAt", d.updatedAt(), "expiresAt", d.expiresAt())));
        body.put("applications", applications);
        return body;
    }

    @GetMapping("/bff/applications/{applicationId}/status")
    public Map<String, Object> status(@PathVariable String applicationId) {
        String customer = CustomerSession.customerKey();
        return status(customer, own(customer, applicationId));
    }

    @PostMapping("/bff/applications/{applicationId}/withdrawal")
    public Map<String, Object> withdraw(@PathVariable String applicationId, HttpServletRequest request) {
        String customer = CustomerSession.customerKey();
        Owned o = own(customer, applicationId);
        requireStepUp(request);
        try {
            gateway.withdraw(customer, o.applicationId(), "cw-withdraw-" + o.applicationId());
        } catch (UpstreamException e) {
            if ("WITHDRAWAL_REFUSED".equals(e.errorCode())) {
                throw new ChannelException(409, "WITHDRAWAL_REFUSED");
            }
            throw unavailable(e);
        }
        meters.counter("cw.withdrawal").increment();
        return status(customer, o);
    }

    /**
     * The customer's answer to their offer (W10.04). Needs a fresh step-up code, which the answer
     * uses up: one code authorises exactly one answer. The platform checks the offer id and terms
     * hash against the offer it holds and books only after an acceptance.
     */
    @PostMapping("/bff/applications/{applicationId}/offer")
    public Map<String, Object> answerOffer(@PathVariable String applicationId, @RequestBody OfferAnswer answer,
                                           HttpServletRequest request) {
        String customer = CustomerSession.customerKey();
        Owned o = own(customer, applicationId);
        UUID offerId;
        try {
            offerId = UUID.fromString(String.valueOf(answer.offerId()));
        } catch (IllegalArgumentException e) {
            throw new ChannelException(400, "VALIDATION_FAILED");
        }
        if (!ANSWERS.contains(answer.decision()) || answer.termsHash() == null || !HASH.matcher(answer.termsHash()).matches()) {
            throw new ChannelException(400, "VALIDATION_FAILED");
        }
        requireStepUp(request);
        Object ref = request.getSession(false).getAttribute(CustomerSession.STEP_UP_REF);
        try {
            gateway.decideOffer(customer, o.applicationId(), "cw-offer-" + o.applicationId(), offerId,
                    answer.termsHash(), answer.decision(), ref instanceof String r ? r : "step-up");
        } catch (UpstreamException e) {
            if ("OFFER_REFUSED".equals(e.errorCode())) {
                meters.counter("cw.offer", "result", "refused").increment();
                throw new ChannelException(409, "OFFER_REFUSED");
            }
            throw unavailable(e);
        }
        request.getSession(false).removeAttribute(CustomerSession.STEP_UP_AT);
        request.getSession(false).removeAttribute(CustomerSession.STEP_UP_REF);
        meters.counter("cw.offer", "result", answer.decision().toLowerCase()).increment();
        return status(customer, o);
    }

    @PostMapping("/bff/applications/{applicationId}/erasure-request")
    public ResponseEntity<Map<String, Object>> erasure(@PathVariable String applicationId, HttpServletRequest request) {
        String customer = CustomerSession.customerKey();
        Owned o = own(customer, applicationId);
        requireStepUp(request);
        JsonNode r;
        try {
            r = gateway.requestErasure(customer, o.applicationId(), "cw-erasure-" + o.applicationId());
        } catch (UpstreamException e) {
            throw unavailable(e);
        }
        return ResponseEntity.accepted().body(Map.of("requestRef", r.path("requestId").asText(), "status", r.path("status").asText()));
    }

    private Map<String, Object> status(String customer, Owned o) {
        JsonNode view;
        try {
            view = gateway.nextAction(customer, o.applicationId());
        } catch (UpstreamException e) {
            throw unavailable(e);
        }
        String status = view.path("customerStatus").asText();
        if (!KNOWN.contains(status)) {
            log.warn("UNKNOWN_CUSTOMER_STATUS application={}", o.applicationId());
            status = "IN_PROGRESS";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationRef", o.applicationRef());
        body.put("customerStatus", status);
        body.put("nextAction", view.path("nextAction").path("type").asText("NONE"));
        body.put("canWithdraw", view.path("canWithdraw").asBoolean(false));
        body.put("updatedAt", view.path("updatedAt").asText());
        JsonNode offer = view.path("offer");
        if ("ACCEPT_OFFER".equals(body.get("nextAction")) && offer.isObject()) {
            // Built field by field: the customer-facing terms only, never the upstream body.
            Map<String, Object> terms = new LinkedHashMap<>();
            for (String f : List.of("offerId", "productCode", "termsVersion", "termsHash", "expiresAt")) {
                terms.put(f, offer.path(f).asText());
            }
            for (String f : List.of("creditLimit", "joiningFee", "annualFee", "monthlyRatePercent", "annualRatePercent")) {
                terms.put(f, offer.path(f).decimalValue());
            }
            body.put("offer", terms);
        }
        body.put("checkedAt", clock.instant());
        return body;
    }

    private List<Owned> owned(String customer) {
        return db.sql("SELECT application_id, application_ref, product, created_at FROM customer_application "
                        + "WHERE customer_key = ? ORDER BY created_at DESC")
                .param(customer)
                .query((rs, n) -> new Owned(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
    }

    /** Another customer's application, or an id that is not a UUID, is "not found", with no upstream call. */
    private Owned own(String customer, String applicationId) {
        UUID id;
        try {
            id = UUID.fromString(applicationId);
        } catch (IllegalArgumentException e) {
            throw new ChannelException(404, "NOT_FOUND");
        }
        return owned(customer).stream().filter(o -> o.applicationId().equals(id)).findFirst().orElseGet(() -> {
            meters.counter("cw.ownership.denied").increment();
            log.warn("OWNERSHIP_DENIED application={}", id);
            throw new ChannelException(404, "NOT_FOUND");
        });
    }

    private void requireStepUp(HttpServletRequest request) {
        Object at = request.getSession(false).getAttribute(CustomerSession.STEP_UP_AT);
        if (!(at instanceof Long millis) || clock.millis() - millis > properties.stepUpValidity().toMillis()) {
            throw new ChannelException(403, "STEP_UP_REQUIRED");
        }
    }

    private static ChannelException unavailable(UpstreamException e) {
        if (e.status() == 404) {
            return new ChannelException(404, "NOT_FOUND");
        }
        return new ChannelException(e.status() == 429 ? 429 : 503, e.status() == 429 ? "RATE_LIMITED" : "TRY_AGAIN",
                List.of(), e.retryAfterSeconds() != null ? e.retryAfterSeconds() : 30L, null);
    }
}
