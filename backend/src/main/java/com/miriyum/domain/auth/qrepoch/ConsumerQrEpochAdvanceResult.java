package com.miriyum.domain.auth.qrepoch;

record ConsumerQrEpochAdvanceResult(Status status) {

    public enum Status {
        APPLIED,
        ALREADY_APPLIED,
        NOT_AUTHORIZED
    }
}
