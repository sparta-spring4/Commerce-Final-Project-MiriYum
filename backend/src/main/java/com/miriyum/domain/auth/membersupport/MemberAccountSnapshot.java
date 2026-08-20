package com.miriyum.domain.auth.membersupport;

import java.time.Instant;
import java.util.Objects;

public record MemberAccountSnapshot(
        MemberAccountType accountType,
        long accountId,
        boolean passwordResetRequired,
        boolean suspended,
        Instant joinedAt,
        long supportVersion
) {
    public MemberAccountSnapshot {
        Objects.requireNonNull(accountType);
        Objects.requireNonNull(joinedAt);
        if (accountId < 1 || supportVersion < 0) throw new IllegalArgumentException("invalid account snapshot");
    }
}
