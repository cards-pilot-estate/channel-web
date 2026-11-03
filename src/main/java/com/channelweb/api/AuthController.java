package com.channelweb.api;

import com.channelweb.domain.ChannelException;
import com.channelweb.domain.CustomerKeys;
import com.channelweb.security.CustomerSession;
import com.channelweb.upstream.GatewayClient;
import com.channelweb.upstream.ProofVerifier;
import com.channelweb.upstream.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mobile and one-time-code sign-in, and step-up before withdrawal or erasure. The pending challenge
 * and a binding nonce live in the session; otp-service sees only the nonce's hash, and its proof is
 * checked before the customer is signed in. The same answer for every valid number (no enumeration).
 */
@RestController
public class AuthController {

    static final String PENDING = "otp.pending";
    static final String USED_PROOFS = "otp.usedProofs";
    static final String SENDS = "otp.sends";
    static final int MAX_SENDS_PER_SESSION = 5;

    /** What the session keeps about a code in flight. Serializable for the Redis session store. */
    public record Pending(String purpose, String challengeId, String mobile, String customerKey, String bindingHash)
            implements java.io.Serializable { }

    public record SendRequest(String mobile) { }

    public record CodeRequest(String code) { }

    private final GatewayClient gateway;
    private final ProofVerifier proofs;
    private final CustomerKeys keys;
    private final CustomerSession sessions;
    private final MeterRegistry meters;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthController(GatewayClient gateway, ProofVerifier proofs, CustomerKeys keys, CustomerSession sessions,
                          MeterRegistry meters, Clock clock) {
        this.gateway = gateway;
        this.proofs = proofs;
        this.keys = keys;
        this.sessions = sessions;
        this.meters = meters;
        this.clock = clock;
    }

    @PostMapping("/bff/auth/otp/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest body, HttpServletRequest request) {
        String mobile = CustomerKeys.normalise(body.mobile());
        if (mobile == null) {
            throw new ChannelException(400, "VALIDATION_FAILED",
                    java.util.List.of(new com.channelweb.domain.Sections.FieldError("mobile", "format")), null, null);
        }
        return ResponseEntity.accepted().body(start("SIGN_IN", mobile, request.getSession(true)));
    }

    @PostMapping("/bff/auth/otp/resend")
    public ResponseEntity<Map<String, Object>> resend(HttpServletRequest request) {
        return ResponseEntity.accepted().body(again(request.getSession(false)));
    }

    @PostMapping("/bff/auth/otp/verify")
    public Map<String, Object> verify(@RequestBody CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        Pending p = finish("SIGN_IN", body.code(), request.getSession(false));
        sessions.signIn(p.customerKey(), p.mobile(), request, response);
        meters.counter("cw.signin", "result", "verified").increment();
        return Map.of("next", "/apply");
    }

    @PostMapping("/bff/auth/step-up/send")
    public ResponseEntity<Map<String, Object>> stepUpSend(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return ResponseEntity.accepted().body(start("STEP_UP", CustomerSession.mobile(session), session));
    }

    @PostMapping("/bff/auth/step-up/verify")
    public Map<String, Object> stepUpVerify(@RequestBody CodeRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Pending p = finish("STEP_UP", body.code(), session);
        if (!p.customerKey().equals(CustomerSession.customerKey())) {
            throw new ChannelException(403, "STEP_UP_MISMATCH");
        }
        request.changeSessionId();
        request.getSession().setAttribute(CustomerSession.STEP_UP_AT, clock.millis());
        return Map.of("verified", true);
    }

    private Map<String, Object> start(String purpose, String mobile, HttpSession session) {
        Integer sends = (Integer) session.getAttribute(SENDS);
        if (sends != null && sends >= MAX_SENDS_PER_SESSION) {
            throw new ChannelException(429, "TOO_MANY_ATTEMPTS", java.util.List.of(), 900L, null);
        }
        String customerKey = keys.keyOf(mobile);
        String bindingHash = bindingHash();
        JsonNode challenge = upstream(() -> gateway.sendOtp(customerKey, UUID.randomUUID().toString(), purpose, mobile, bindingHash));
        session.setAttribute(SENDS, sends == null ? 1 : sends + 1);
        session.setAttribute(PENDING, new Pending(purpose, challenge.path("challengeId").asText(), mobile, customerKey, bindingHash));
        return view(challenge, mobile);
    }

    private Map<String, Object> again(HttpSession session) {
        Pending p = session == null ? null : (Pending) session.getAttribute(PENDING);
        if (p == null) {
            throw new ChannelException(409, "NO_PENDING_CODE");
        }
        JsonNode challenge = upstream(() -> gateway.resendOtp(p.customerKey(), p.challengeId(), p.mobile(), p.bindingHash()));
        return view(challenge, p.mobile());
    }

    @SuppressWarnings("unchecked")
    private Pending finish(String purpose, String code, HttpSession session) {
        Pending p = session == null ? null : (Pending) session.getAttribute(PENDING);
        if (p == null || !p.purpose().equals(purpose)) {
            throw new ChannelException(409, "NO_PENDING_CODE");
        }
        String clean = code == null ? "" : code.replaceAll("\\s", "");
        String proof = upstream(() -> gateway.verifyOtp(p.customerKey(), p.challengeId(), clean, p.bindingHash()));
        String jti = proofs.verify(proof, p.customerKey(), purpose, p.challengeId(), p.bindingHash());
        Set<String> used = (Set<String>) session.getAttribute(USED_PROOFS);
        HashSet<String> next = used == null ? new HashSet<>() : new HashSet<>(used);
        if (!next.add(jti)) {
            throw new ChannelException(409, "NO_PENDING_CODE");
        }
        session.setAttribute(USED_PROOFS, next);
        session.removeAttribute(PENDING);
        session.removeAttribute(SENDS);
        return p;
    }

    private static Map<String, Object> view(JsonNode challenge, String mobile) {
        return Map.of("maskedMobile", CustomerKeys.masked(mobile),
                "expiresAt", challenge.path("expiresAt").asText(),
                "resendAvailableAt", challenge.path("resendAvailableAt").asText(),
                "codeLength", challenge.path("codeLength").asInt(6));
    }

    /** otp-service's refusals become the customer's: wrong code, expired, too many, try later. */
    private static <T> T upstream(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (UpstreamException e) {
            int status = switch (e.errorCode()) {
                case "OTP_INCORRECT" -> 422;
                case "OTP_EXPIRED", "OTP_ATTEMPTS_EXHAUSTED", "OTP_NOT_FOUND" -> 410;
                case "OTP_SEND_LIMIT", "OTP_RESEND_TOO_SOON", "RATE_LIMITED" -> 429;
                default -> e.transientFailure() ? 503 : 502;
            };
            String code = "OTP_NOT_FOUND".equals(e.errorCode()) ? "OTP_EXPIRED"
                    : status >= 502 ? "SERVICE_UNAVAILABLE" : e.errorCode();
            throw new ChannelException(status, code, java.util.List.of(), e.retryAfterSeconds(), e.attemptsRemaining());
        }
    }

    private String bindingHash() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getUrlEncoder().withoutPadding().encodeToString(nonce).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
