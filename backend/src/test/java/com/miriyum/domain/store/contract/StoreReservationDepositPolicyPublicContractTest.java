package com.miriyum.domain.store.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy.Status;
import com.miriyum.domain.store.service.StoreReservationDepositPolicyQueryService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class StoreReservationDepositPolicyPublicContractTest {

    private static final List<String> FORBIDDEN_PUBLIC_TYPE_FRAGMENTS = List.of(
            ".entity.",
            ".repository.",
            ".dto.storeoperator.");

    @Test
    @DisplayName("예약금 정책 DTO는 승인된 공개 ABI만 노출한다")
    void exposesApprovedRecordAbi() {
        assertThat(StoreReservationDepositPolicy.class.getRecordComponents())
                .extracting(RecordComponent::getName, RecordComponent::getType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("storeId", long.class),
                        org.assertj.core.groups.Tuple.tuple("status", Status.class),
                        org.assertj.core.groups.Tuple.tuple("ratePercent", OptionalInt.class),
                        org.assertj.core.groups.Tuple.tuple("policyVersion", OptionalLong.class));
        assertThat(Status.values())
                .containsExactly(Status.UNCONFIGURED, Status.DISABLED, Status.ENABLED);
    }

    @Test
    @DisplayName("미구성 상태는 비율과 정책 revision을 모두 비워 둔다")
    void unconfiguredRequiresAbsentRateAndPolicyVersion() {
        StoreReservationDepositPolicy policy = new StoreReservationDepositPolicy(
                1L, Status.UNCONFIGURED, OptionalInt.empty(), OptionalLong.empty());

        assertThat(policy.ratePercent()).isEmpty();
        assertThat(policy.policyVersion()).isEmpty();
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.UNCONFIGURED, OptionalInt.of(10), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.UNCONFIGURED, OptionalInt.empty(), OptionalLong.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("구성 상태는 10~30 비율과 양수 정책 revision을 모두 보관한다")
    void configuredStatusesRequireValidRateAndPositivePolicyVersion() {
        assertThat(new StoreReservationDepositPolicy(
                1L, Status.DISABLED, OptionalInt.of(10), OptionalLong.of(1L)).status())
                .isEqualTo(Status.DISABLED);
        assertThat(new StoreReservationDepositPolicy(
                1L, Status.ENABLED, OptionalInt.of(30), OptionalLong.of(2L)).status())
                .isEqualTo(Status.ENABLED);

        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.DISABLED, OptionalInt.empty(), OptionalLong.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.ENABLED, OptionalInt.of(10), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.ENABLED, OptionalInt.of(9), OptionalLong.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.ENABLED, OptionalInt.of(31), OptionalLong.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.ENABLED, OptionalInt.of(10), OptionalLong.of(0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null은 예약금 정책 값의 부재 표현으로 사용할 수 없다")
    void rejectsNullContractValues() {
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, null, OptionalInt.empty(), OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.UNCONFIGURED, null, OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoreReservationDepositPolicy(
                1L, Status.UNCONFIGURED, OptionalInt.empty(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("현재 정책 조회는 caller transaction에 read-only로 참여한다")
    void exposesMandatoryReadOnlyGetCurrentMethod() throws Exception {
        Method getCurrent = StoreReservationDepositPolicyQueryService.class.getDeclaredMethod(
                "getCurrent", long.class);

        assertThat(Modifier.isPublic(getCurrent.getModifiers())).isTrue();
        assertThat(getCurrent.getReturnType()).isEqualTo(StoreReservationDepositPolicy.class);
        assertThat(getCurrent.getParameterTypes()).containsExactly(long.class);
        Transactional transactional = getCurrent.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(transactional.timeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
    }

    @Test
    @DisplayName("공개 조회 계약은 Store 내부 타입을 노출하지 않는다")
    void publicContractDoesNotExposeStoreInternals() {
        List<Class<?>> publicMethodTypes = Arrays.stream(
                        StoreReservationDepositPolicyQueryService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(concat(
                        method.getReturnType(), method.getParameterTypes())))
                .toList();
        List<Class<?>> dtoComponentTypes = Arrays.stream(
                        StoreReservationDepositPolicy.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();

        assertThat(publicMethodTypes)
                .allSatisfy(this::assertNotStoreInternalType);
        assertThat(dtoComponentTypes)
                .allSatisfy(this::assertNotStoreInternalType);
    }

    private void assertNotStoreInternalType(Class<?> type) {
        assertThat(FORBIDDEN_PUBLIC_TYPE_FRAGMENTS)
                .allSatisfy(fragment -> assertThat(type.getName()).doesNotContain(fragment));
    }

    private static Class<?>[] concat(Class<?> first, Class<?>[] rest) {
        Class<?>[] result = new Class<?>[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
