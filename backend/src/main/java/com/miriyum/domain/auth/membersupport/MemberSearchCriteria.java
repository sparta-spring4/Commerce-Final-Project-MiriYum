package com.miriyum.domain.auth.membersupport;

import java.time.Instant;

public record MemberSearchCriteria(Instant joinedFrom, Instant joinedTo) {
    public MemberSearchCriteria {
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw new IllegalArgumentException("joinedFrom must not be after joinedTo");
        }
    }
}
