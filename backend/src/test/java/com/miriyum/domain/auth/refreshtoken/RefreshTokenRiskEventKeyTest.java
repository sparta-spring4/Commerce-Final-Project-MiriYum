package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenRiskEventKeyTest {

    @Test
    @DisplayName("pending 인덱스는 marker 조회 패턴에 포함되지 않는다")
    void pendingIndexIsOutsidePendingMarkerPattern() {
        String markerPrefix = RefreshTokenRiskEventKey.pendingPattern().replace("*", "");

        assertThat(RefreshTokenRiskEventKey.pendingIndex())
                .doesNotStartWith(markerPrefix);
    }
}
