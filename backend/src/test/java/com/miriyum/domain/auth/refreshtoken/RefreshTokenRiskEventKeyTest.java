package com.miriyum.domain.auth.refreshtoken;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenRiskEventKeyTest {

    @Test
    @DisplayName("pending 인덱스는 marker 조회 패턴에 포함되지 않는다")
    void pendingIndexIsOutsidePendingMarkerPattern() {
        String markerPattern = RefreshTokenRiskEventKey.pendingPattern();
        assertThat(markerPattern).endsWith("*");

        String markerPrefix = markerPattern.substring(0, markerPattern.length() - 1);

        assertThat(RefreshTokenRiskEventKey.pendingIndex())
                .doesNotStartWith(markerPrefix);
    }
}
