package com.miriyum.domain.store.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreReservationDepositPolicyTest {

    @Test
    @DisplayName("최초 설정은 비율과 사용 여부를 보관하고 두 버전을 초기화한다")
    void createInitializesConfiguredPolicyAndVersions() {
        StoreReservationDepositPolicy policy =
                StoreReservationDepositPolicy.create(7L, true, 20);

        assertThat(policy.getStoreId()).isEqualTo(7L);
        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.getRatePercent()).isEqualTo(20);
        assertThat(policy.getPolicyVersion()).isEqualTo(1L);
        assertThat(policy.getLockVersion()).isZero();
    }

    @Test
    @DisplayName("양수가 아닌 매장 ID로 최초 정책을 만들 수 없다")
    void createRejectsNonPositiveStoreId() {
        assertThatThrownBy(() -> StoreReservationDepositPolicy.create(0L, true, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 31})
    @DisplayName("10~30 밖의 비율로 최초 정책을 만들 수 없다")
    void createRejectsOutOfRangeRatePercent(int ratePercent) {
        assertThatThrownBy(() ->
                StoreReservationDepositPolicy.create(7L, true, ratePercent))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사용 여부나 비율이 실제로 바뀌면 정책 버전을 한 번 올린다")
    void updateChangesPolicyAndIncrementsPolicyVersion() {
        StoreReservationDepositPolicy policy =
                StoreReservationDepositPolicy.create(7L, true, 20);

        boolean changed = policy.update(false, 25);

        assertThat(changed).isTrue();
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getRatePercent()).isEqualTo(25);
        assertThat(policy.getPolicyVersion()).isEqualTo(2L);
        assertThat(policy.getLockVersion()).isZero();
    }

    @Test
    @DisplayName("동일 설정 저장은 값과 정책 버전을 바꾸지 않는 완전한 no-op이다")
    void updateSameValuesIsNoOp() {
        StoreReservationDepositPolicy policy =
                StoreReservationDepositPolicy.create(7L, false, 20);

        boolean changed = policy.update(false, 20);

        assertThat(changed).isFalse();
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getRatePercent()).isEqualTo(20);
        assertThat(policy.getPolicyVersion()).isEqualTo(1L);
        assertThat(policy.getLockVersion()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 31})
    @DisplayName("범위 밖 비율 변경은 기존 설정과 정책 버전을 보존한 채 거부한다")
    void updateRejectsOutOfRangeRateBeforeMutation(int ratePercent) {
        StoreReservationDepositPolicy policy =
                StoreReservationDepositPolicy.create(7L, true, 20);

        assertThatThrownBy(() -> policy.update(false, ratePercent))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.getRatePercent()).isEqualTo(20);
        assertThat(policy.getPolicyVersion()).isEqualTo(1L);
    }
}
