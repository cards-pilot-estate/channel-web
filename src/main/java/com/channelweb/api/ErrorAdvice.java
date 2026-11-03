package com.channelweb.api;

import com.channelweb.domain.ChannelException;
import com.channelweb.upstream.ProofVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every error is a code the SPA translates, never an upstream body or a stack trace. */
@RestControllerAdvice
public class ErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

    @ExceptionHandler(ChannelException.class)
    ResponseEntity<Map<String, Object>> channel(ChannelException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", e.errorCode());
        if (!e.fieldErrors().isEmpty()) body.put("fieldErrors", e.fieldErrors());
        if (e.attemptsRemaining() != null) body.put("attemptsRemaining", e.attemptsRemaining());
        if (e.retryAfterSeconds() != null) body.put("retryAfterSeconds", e.retryAfterSeconds());
        var response = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) response.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        return response.body(body);
    }

    @ExceptionHandler(ProofVerifier.InvalidProofException.class)
    ResponseEntity<Map<String, Object>> proof(ProofVerifier.InvalidProofException e) {
        log.warn("OTP_PROOF_REJECTED {}", e.getMessage());
        return ResponseEntity.status(502).body(Map.of("errorCode", "SERVICE_UNAVAILABLE"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<Map<String, Object>> malformed(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("errorCode", "MALFORMED_REQUEST"));
    }
}
