package com.miriyum.domain.analytics.service;

/** 공개 source DTO가 Analytics가 요청한 snapshot 경계를 위반했음을 나타낸다. */
final class SourceSnapshotBoundaryMismatchException extends RuntimeException {

    SourceSnapshotBoundaryMismatchException() {
        super("source snapshot boundary mismatch");
    }
}
