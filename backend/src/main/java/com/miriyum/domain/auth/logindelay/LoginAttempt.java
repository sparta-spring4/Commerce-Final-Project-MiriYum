package com.miriyum.domain.auth.logindelay;

/**
 * The result of atomically starting one account's password comparison.
 */
public record LoginAttempt(Status status, String token) {

    public enum Status {
        ACQUIRED,
        BUSY,
        DELAYED
    }

    public static LoginAttempt acquired(String token) {
        return new LoginAttempt(Status.ACQUIRED, token);
    }

    public static LoginAttempt busy() {
        return new LoginAttempt(Status.BUSY, null);
    }

    public static LoginAttempt delayed() {
        return new LoginAttempt(Status.DELAYED, null);
    }
}
