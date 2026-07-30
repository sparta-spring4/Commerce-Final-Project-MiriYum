package com.miriyum.domain.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.store.core.dto.ManagedStoreResponse;
import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final String IDEMPOTENCY_KEY = "123e4567-e89b-12d3-a456-426614174000";

    @Mock
    private StoreOperatorAccountService operatorAccountService;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreCatalogPolicy catalogPolicy;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private ObjectMapper objectMapper;
    private StoreService storeService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storeService = new StoreService(
                operatorAccountService,
                storeRepository,
                catalogPolicy,
                idempotencyExecutor,
                objectMapper);
    }

    @Test
    void createsStoreThroughIdempotentCommand() {
        StoreCreateRequest request = validCreateRequest();
        AtomicReference<BusinessResult<?>> result = runBusinessWorkOnExecute();
        given(storeRepository.saveAndFlush(any(Store.class))).willAnswer(invocation -> {
            Store store = invocation.getArgument(0);
            ReflectionTestUtils.setField(store, "id", STORE_ID);
            return store;
        });

        StoreCommandResult commandResult =
                storeService.create(OPERATOR_ID, IdempotencyKey.parse(IDEMPOTENCY_KEY), request);

        assertThat(commandResult.httpStatus()).isEqualTo(201);
        assertThat(commandResult.data().storeId()).isEqualTo(STORE_ID);
        assertThat(result.get().resourceType()).isEqualTo("STORE");
        then(operatorAccountService).should().getMe(OPERATOR_ID);
        then(catalogPolicy).should().validate("CAFE_BAKERY", List.of("DATE"));
        then(storeRepository).should().saveAndFlush(any(Store.class));
    }

    @Test
    void buildsCreateCommandFromPrincipalKeyAndRequestFingerprint() {
        StoreCreateRequest request = validCreateRequest();
        AtomicReference<IdempotencyCommand> command = new AtomicReference<>();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            command.set(invocation.getArgument(0));
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return outcome(result, (ManagedStoreResponse) result.data());
        });
        given(storeRepository.saveAndFlush(any(Store.class))).willAnswer(invocation -> {
            Store store = invocation.getArgument(0);
            ReflectionTestUtils.setField(store, "id", STORE_ID);
            return store;
        });

        storeService.create(OPERATOR_ID, IdempotencyKey.parse(IDEMPOTENCY_KEY), request);

        assertThat(command.get().principalNamespace()).isEqualTo("store-operator");
        assertThat(command.get().principalId()).isEqualTo(OPERATOR_ID);
        assertThat(command.get().commandType()).isEqualTo("STORE_REGISTER");
        assertThat(command.get().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(command.get().requestFingerprint())
                .isEqualTo(StoreCommandFingerprint.forCreate(request));
    }

    @Test
    void rejectsMissingStore() {
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }

    @Test
    void rejectsStoreOwnedByAnotherOperator() {
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(storeOwnedBy(12L)));

        assertThatThrownBy(() -> storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.ACCESS_DENIED);
    }

    @Test
    void exposesOnlyManagementAuthorityState() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store));

        StoreManagementView view =
                storeService.requireManagementAuthority(OPERATOR_ID, STORE_ID);

        assertThat(view.storeId()).isEqualTo(STORE_ID);
        assertThat(view.operationStatus()).isEqualTo(OperationStatus.OPEN);
        assertThat(view.verificationStatus()).isEqualTo(store.getVerificationStatus());
        assertThat(view.pickupEligibility()).isEqualTo(store.getPickupEligibility());
    }

    @Test
    void updateValidatesEffectiveCatalogCombinationAndChangesStore() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        StoreUpdateRequest request = new StoreUpdateRequest(
                "새 이름", null, null, null, null, List.of("QUIET"),
                new StoreModesRequest(false, true, false), null);
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store));
        given(storeRepository.saveAndFlush(store)).willReturn(store);
        runBusinessWorkOnExecute();

        StoreCommandResult result = storeService.update(
                OPERATOR_ID, STORE_ID, IdempotencyKey.parse(IDEMPOTENCY_KEY), request);

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(store.getName()).isEqualTo("새 이름");
        assertThat(store.getTagCodes()).containsExactly("QUIET");
        assertThat(store.isReservationEnabled()).isFalse();
        then(catalogPolicy).should().validate("CAFE_BAKERY", List.of("QUIET"));
    }

    @Test
    void mapsActiveBusinessNumberConstraintToConflict() {
        given(storeRepository.saveAndFlush(any(Store.class)))
                .willThrow(new DataIntegrityViolationException(
                        "uk_stores_active_business_number"));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.BUSINESS_NUMBER_CONFLICT);
    }

    @Test
    void doesNotHideUnrelatedIntegrityViolation() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("some_other_constraint");
        given(storeRepository.saveAndFlush(any(Store.class))).willThrow(failure);
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest()))
                .isSameAs(failure);
    }

    private AtomicReference<BusinessResult<?>> runBusinessWorkOnExecute() {
        AtomicReference<BusinessResult<?>> captured = new AtomicReference<>();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            captured.set(result);
            return outcome(result, (ManagedStoreResponse) result.data());
        });
        return captured;
    }

    private IdempotentOutcome outcome(
            BusinessResult<?> result,
            ManagedStoreResponse response
    ) {
        return new IdempotentOutcome(
                false,
                result.httpStatus(),
                result.responseCode(),
                result.resourceType(),
                result.resourceId(),
                objectMapper.valueToTree(response));
    }

    private StoreCreateRequest validCreateRequest() {
        return new StoreCreateRequest(
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                List.of("DATE"),
                new StoreModesRequest(true, true, true));
    }

    private Store storeOwnedBy(long operatorId) {
        return Store.create(
                operatorId,
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                true,
                true);
    }
}
