package com.miriyum.global.exception;

/**
 * A service error that asks the client to retry after a short delay.
 */
public class RetryableServiceException extends ServiceException {

    private final long retryAfterSeconds;

    public RetryableServiceException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
