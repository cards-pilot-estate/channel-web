package com.channelweb.upstream;

/** A refusal or failure from the gateway, the platform or otp-service, as the BFF must handle it. */
public class UpstreamException extends RuntimeException {

    private final int status;
    private final String errorCode;
    private final Long retryAfterSeconds;
    private final Integer attemptsRemaining;

    public UpstreamException(int status, String errorCode, Long retryAfterSeconds, Integer attemptsRemaining) {
        super(errorCode + " (" + status + ")");
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.attemptsRemaining = attemptsRemaining;
    }

    public int status() { return status; }
    public String errorCode() { return errorCode; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
    public Integer attemptsRemaining() { return attemptsRemaining; }

    public boolean transientFailure() {
        return status == 429 || status >= 500 || status == 0;
    }
}
