package com.channelweb.domain;

import java.util.List;

/** A refusal the BFF gives the SPA: status, code, and optionally field errors or a retry time. */
public class ChannelException extends RuntimeException {

    private final int status;
    private final String errorCode;
    private final List<Sections.FieldError> fieldErrors;
    private final Long retryAfterSeconds;
    private final Integer attemptsRemaining;

    public ChannelException(int status, String errorCode) {
        this(status, errorCode, List.of(), null, null);
    }

    public ChannelException(int status, String errorCode, List<Sections.FieldError> fieldErrors, Long retryAfterSeconds,
                            Integer attemptsRemaining) {
        super(errorCode);
        this.status = status;
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
        this.retryAfterSeconds = retryAfterSeconds;
        this.attemptsRemaining = attemptsRemaining;
    }

    public int status() { return status; }
    public String errorCode() { return errorCode; }
    public List<Sections.FieldError> fieldErrors() { return fieldErrors; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
    public Integer attemptsRemaining() { return attemptsRemaining; }
}
