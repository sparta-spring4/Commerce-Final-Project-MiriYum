package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCheckInQrGrantTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-16T01:00:00Z");
    private static final ConsumerQrEpochSnapshot EPOCH = new ConsumerQrEpochSnapshot(
            11L,
            "v1." + "A".repeat(43)
    );

    @Test
    @DisplayName("첫 grant는 version 1과 정확한 30초 만료를 가진다")
    void issuesFirstGrant() {
        byte[] digest = digest((byte) 1);

        ReservationCheckInQrGrant grant = ReservationCheckInQrGrant.issue(
                77L, digest, EPOCH, ISSUED_AT
        );

        assertThat(grant.getReservationId()).isEqualTo(77L);
        assertThat(grant.getTokenVersion()).isEqualTo(1L);
        assertThat(grant.getTokenDigest()).containsExactly(digest);
        assertThat(grant.getQrEpochAccountId()).isEqualTo(11L);
        assertThat(grant.getQrEpochOpaqueVersion()).isEqualTo(EPOCH.opaqueVersion());
        assertThat(grant.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(grant.getExpiresAt()).isEqualTo(ISSUED_AT.plusSeconds(30));
        assertThat(grant.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("rotation은 version을 증가시키고 이전 digest와 epoch를 즉시 교체한다")
    void rotatesCurrentGrantWithoutOverlap() {
        ReservationCheckInQrGrant grant = ReservationCheckInQrGrant.issue(
                77L, digest((byte) 1), EPOCH, ISSUED_AT
        );
        ConsumerQrEpochSnapshot nextEpoch = new ConsumerQrEpochSnapshot(
                11L,
                "v1." + "B".repeat(43)
        );

        grant.rotate(digest((byte) 2), nextEpoch, ISSUED_AT.plusSeconds(5));

        assertThat(grant.getTokenVersion()).isEqualTo(2L);
        assertThat(grant.getTokenDigest()).containsExactly(digest((byte) 2));
        assertThat(grant.getQrEpochOpaqueVersion()).isEqualTo(nextEpoch.opaqueVersion());
        assertThat(grant.getIssuedAt()).isEqualTo(ISSUED_AT.plusSeconds(5));
        assertThat(grant.getExpiresAt()).isEqualTo(ISSUED_AT.plusSeconds(35));
        assertThat(grant.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("grant는 발급 시각 포함·만료 시각 제외이며 소비 뒤 사용할 수 없다")
    void enforcesHalfOpenTtlAndOneTimeConsumption() {
        byte[] digest = digest((byte) 3);
        ReservationCheckInQrGrant grant = ReservationCheckInQrGrant.issue(
                77L, digest, EPOCH, ISSUED_AT
        );

        assertThat(grant.isUsable(digest, ISSUED_AT)).isTrue();
        assertThat(grant.isUsable(digest, ISSUED_AT.plusSeconds(30).minusNanos(1))).isTrue();
        assertThat(grant.isUsable(digest, ISSUED_AT.plusSeconds(30))).isFalse();
        assertThat(grant.isUsable(digest((byte) 4), ISSUED_AT.plusSeconds(1))).isFalse();

        grant.consume(ISSUED_AT.plusSeconds(1));

        assertThat(grant.getConsumedAt()).isEqualTo(ISSUED_AT.plusSeconds(1));
        assertThat(grant.isUsable(digest, ISSUED_AT.plusSeconds(2))).isFalse();
        assertThatThrownBy(() -> grant.consume(ISSUED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("digest 길이와 epoch 계정은 저장 계약을 만족해야 한다")
    void rejectsInvalidDigestAndEpoch() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCheckInQrGrant.issue(77L, new byte[31], EPOCH, ISSUED_AT)
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCheckInQrGrant.issue(77L, digest((byte) 1), null, ISSUED_AT)
        );
    }

    private static byte[] digest(byte value) {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, value);
        return digest;
    }
}
