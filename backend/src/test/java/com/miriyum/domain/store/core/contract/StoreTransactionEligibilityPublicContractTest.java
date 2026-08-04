package com.miriyum.domain.store.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.core.dto.StorePickupTransactionEligibility;
import com.miriyum.domain.store.core.dto.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.core.service.StoreTransactionEligibilityService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class StoreTransactionEligibilityPublicContractTest {

    private static final List<String> FORBIDDEN_PUBLIC_TYPE_FRAGMENTS = List.of(
            ".entity.",
            ".repository.",
            ".enums.",
            "ManagedStoreResponse");

    @Test
    void exposesPurposeSpecificStoreIdOnlyMethodsWithoutPrincipal() throws Exception {
        Method reservation = StoreTransactionEligibilityService.class.getDeclaredMethod(
                "requireReservationTransactionEligibility", long.class);
        Method pickup = StoreTransactionEligibilityService.class.getDeclaredMethod(
                "requirePickupTransactionEligibility", long.class);

        assertThat(reservation.getReturnType())
                .isEqualTo(StoreReservationTransactionEligibility.class);
        assertThat(pickup.getReturnType())
                .isEqualTo(StorePickupTransactionEligibility.class);
        assertThat(reservation.getParameterTypes()).containsExactly(long.class);
        assertThat(pickup.getParameterTypes()).containsExactly(long.class);
        assertMandatoryLockingTransactionGate(reservation);
        assertMandatoryLockingTransactionGate(pickup);
    }

    @Test
    void purposeSpecificDtosExposeOnlyValidatedStoreId() {
        assertStoreIdOnlyDto(StoreReservationTransactionEligibility.class);
        assertStoreIdOnlyDto(StorePickupTransactionEligibility.class);
    }

    @Test
    void publicApiDoesNotExposeStoreInternalsOrManagementDto() {
        List<Class<?>> publicMethodTypes = Arrays.stream(
                        StoreTransactionEligibilityService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(concat(
                        method.getReturnType(), method.getParameterTypes())))
                .toList();
        List<Class<?>> publicConstructorTypes = Arrays.stream(
                        StoreTransactionEligibilityService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .toList();
        List<Class<?>> dtoComponentTypes = List.of(
                StoreReservationTransactionEligibility.class,
                StorePickupTransactionEligibility.class).stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getType)
                .toList();

        assertThat(publicMethodTypes)
                .allSatisfy(this::assertNotStoreInternalType);
        assertThat(publicConstructorTypes)
                .allSatisfy(this::assertNotStoreInternalType);
        assertThat(dtoComponentTypes)
                .allSatisfy(this::assertNotStoreInternalType);
    }

    private void assertMandatoryLockingTransactionGate(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional)
                .as("%s must declare a transaction boundary", method.getName())
                .isNotNull();
        assertThat(transactional.propagation())
                .as("%s must join an existing creation transaction", method.getName())
                .isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly())
                .as("%s must allow a pessimistic write lock", method.getName())
                .isFalse();
        assertThat(transactional.isolation())
                .as("%s must leave isolation to the creation transaction", method.getName())
                .isEqualTo(Isolation.DEFAULT);
        assertThat(transactional.timeout())
                .as("%s must leave timeout to the creation transaction", method.getName())
                .isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
    }

    private void assertStoreIdOnlyDto(Class<?> dtoType) {
        assertThat(dtoType.isRecord()).isTrue();
        assertThat(dtoType.getRecordComponents())
                .extracting(RecordComponent::getName, RecordComponent::getType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("storeId", long.class));
    }

    private void assertNotStoreInternalType(Class<?> type) {
        assertThat(FORBIDDEN_PUBLIC_TYPE_FRAGMENTS)
                .allSatisfy(fragment ->
                        assertThat(type.getName()).doesNotContain(fragment));
    }

    private static Class<?>[] concat(Class<?> first, Class<?>[] rest) {
        Class<?>[] result = new Class<?>[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
