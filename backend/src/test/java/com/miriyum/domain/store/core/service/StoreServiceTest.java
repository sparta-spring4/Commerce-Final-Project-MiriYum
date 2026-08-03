package com.miriyum.domain.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.store.core.dto.ManagedStoreResponse;
import com.miriyum.domain.store.core.dto.StoreCreateRequest;
import com.miriyum.domain.store.core.dto.StoreModesRequest;
import com.miriyum.domain.store.core.dto.StoreUpdateRequest;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import com.miriyum.domain.storeoperator.service.StoreOperatorAccountService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final long MENU_ID = 21L;
    private static final String IDEMPOTENCY_KEY = "123e4567-e89b-12d3-a456-426614174000";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T03:00:00Z"),
            ZoneOffset.UTC);

    @Mock
    private StoreOperatorAccountService operatorAccountService;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private MenuRepository menuRepository;

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
                menuRepository,
                catalogPolicy,
                idempotencyExecutor,
                objectMapper,
                FIXED_CLOCK);
    }

    @Test
    void createsStoreThroughIdempotentCommand() {
        StoreCreateRequest request = validCreateRequest();
        AtomicReference<BusinessResult<?>> result = runBusinessWorkOnExecute();
        AtomicReference<Store> savedStore = new AtomicReference<>();
        given(storeRepository.saveAndFlush(any(Store.class))).willAnswer(invocation -> {
            Store store = invocation.getArgument(0);
            ReflectionTestUtils.setField(store, "id", STORE_ID);
            savedStore.set(store);
            return store;
        });

        StoreCommandResult commandResult =
                storeService.create(OPERATOR_ID, IdempotencyKey.parse(IDEMPOTENCY_KEY), request);

        assertThat(commandResult.httpStatus()).isEqualTo(201);
        assertThat(commandResult.data().storeId()).isEqualTo(Long.toString(STORE_ID));
        assertThat(result.get().resourceType()).isEqualTo("STORE");
        assertThat(savedStore.get().getApplicantSelfAttestedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
        assertThat(savedStore.get().getRequiredTermsAgreedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
        assertThat(savedStore.get().getRequiredTermsVersion())
                .isEqualTo("STORE_ONBOARDING_REQUIRED_TERMS_V1");
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
    void managementOwnershipChecksOnlyExistenceAndOwner() {
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));

        storeService.requireManagementOwnership(OPERATOR_ID, STORE_ID);

        then(operatorAccountService).should().getMe(OPERATOR_ID);
        then(storeRepository).should().findOperatorAccountIdById(STORE_ID);
        then(storeRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void schedulePublicationAuthorityRejectsClosedStore() {
        Store store = storeOwnedBy(OPERATOR_ID);
        store.close();
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));

        assertThatThrownBy(() ->
                storeService.requireSchedulePublicationAuthority(
                        OPERATOR_ID,
                        STORE_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception ->
                        ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
        then(storeRepository).should().findByIdForUpdate(STORE_ID);
    }

    @Test
    void schedulePublicationAuthorityReturnsLockedStoreTimeZone() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));

        StoreScheduleAuthority authority =
                storeService.requireSchedulePublicationAuthority(
                        OPERATOR_ID,
                        STORE_ID);

        assertThat(authority)
                .isEqualTo(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
    }

    @Test
    void menuMutationAuthorityReturnsLockedStorePickupEligibility() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));

        StoreMenuAuthority authority =
                storeService.requireMenuMutationAuthority(OPERATOR_ID, STORE_ID);

        assertThat(authority)
                .isEqualTo(new StoreMenuAuthority(
                        STORE_ID,
                        com.miriyum.domain.store.core.enums.PickupEligibility.ELIGIBLE));
    }

    @Test
    void transactionEligibilityRejectsMissingStoreBeforeLoadingMenu() {
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);

        then(menuRepository).shouldHaveNoInteractions();
    }

    @Test
    void transactionEligibilityRejectsUnapprovedStoreBeforeLoadingMenu() {
        Store store = transactionStore();
        ReflectionTestUtils.setField(store, "verificationStatus", null);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertThatThrownBy(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.VERIFICATION_STATE_CONFLICT);

        then(menuRepository).shouldHaveNoInteractions();
    }

    @Test
    void transactionEligibilityRejectsTemporarilyClosedStoreBeforeLoadingMenu() {
        Store store = transactionStore();
        ReflectionTestUtils.setField(
                store, "operationStatus", OperationStatus.TEMPORARILY_CLOSED);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertThatThrownBy(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);

        then(menuRepository).shouldHaveNoInteractions();
    }

    @Test
    void transactionEligibilityRejectsClosedStoreBeforeLoadingMenu() {
        Store store = transactionStore();
        store.close();
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertThatThrownBy(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);

        then(menuRepository).shouldHaveNoInteractions();
    }

    @Test
    void transactionEligibilityRejectsMissingMenu() {
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(transactionStore()));
        given(menuRepository.findByIdForUpdate(MENU_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_NOT_FOUND);
    }

    @Test
    void transactionEligibilityHidesWrongStoreOwnershipAsMissingMenu() {
        Menu menu = menu(STORE_ID + 1, true, true);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertThatThrownBy(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_NOT_FOUND);
    }

    @Test
    void transactionEligibilityRejectsUnpublishedMenu() {
        stubTransactionStoreAndMenu(transactionStore(), menu(STORE_ID, true, true));

        assertMenuStateConflict(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsHiddenMenu() {
        Menu menu = publishedMenu(true, true);
        menu.changeVisibility(MenuVisibility.HIDDEN);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsPausedMenu() {
        Menu menu = publishedMenu(true, true);
        menu.changeSellingStatus(MenuSellingStatus.PAUSED);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsSoldOutMenu() {
        Menu menu = publishedMenu(true, true);
        menu.changeSellingStatus(MenuSellingStatus.SOLD_OUT);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsRetiredMenu() {
        Menu menu = publishedMenu(true, true);
        menu.retire(FIXED_CLOCK.instant().plusSeconds(2));
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityReturnsCurrentPublishedVersionAndCapabilities() {
        Menu menu = publishedMenu(true, true);
        menu.appendDraft(menuContent(true, true), OPERATOR_ID,
                FIXED_CLOCK.instant().plusSeconds(2));
        menu.publish(FIXED_CLOCK.instant().plusSeconds(3));
        stubTransactionStoreAndMenu(transactionStore(), menu);

        MenuTransactionEligibility result =
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result).isEqualTo(new MenuTransactionEligibility(
                STORE_ID, MENU_ID, 2, true, true));
        InOrder lockOrder = inOrder(storeRepository, menuRepository);
        lockOrder.verify(storeRepository).findByIdForUpdate(STORE_ID);
        lockOrder.verify(menuRepository).findByIdForUpdate(MENU_ID);
        then(operatorAccountService).shouldHaveNoInteractions();
    }

    @Test
    void transactionEligibilityCombinesPublishedVersionCapabilities() {
        Menu menu = publishedMenu(false, false);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        MenuTransactionEligibility result =
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result.menuHoldEligible()).isFalse();
        assertThat(result.pickupEligible()).isFalse();
    }

    @Test
    void transactionEligibilityCombinesStoreModes() {
        Store store = storeOwnedBy(OPERATOR_ID, BusinessType.CAFE, false, false);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        stubTransactionStoreAndMenu(store, publishedMenu(true, true));

        MenuTransactionEligibility result =
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result.menuHoldEligible()).isFalse();
        assertThat(result.pickupEligible()).isFalse();
    }

    @Test
    void transactionEligibilityCombinesStorePickupEligibility() {
        Store store = transactionStore();
        ReflectionTestUtils.setField(
                store, "pickupEligibility", PickupEligibility.INELIGIBLE);
        stubTransactionStoreAndMenu(store, publishedMenu(true, true));

        MenuTransactionEligibility result =
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result.menuHoldEligible()).isTrue();
        assertThat(result.pickupEligible()).isFalse();
    }

    @Test
    void scheduledActivationDecisionAllowsApprovedStoreWithoutOperatorIdentity() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));

        StoreScheduledActivationDecision decision =
                storeService.inspectScheduledActivation(STORE_ID);

        assertThat(decision.timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(decision.activationAllowed()).isTrue();
        then(operatorAccountService).shouldHaveNoInteractions();
    }

    @Test
    void scheduledActivationDecisionRejectsClosedStoreWithoutThrowing() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        store.close();
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));

        StoreScheduledActivationDecision decision =
                storeService.inspectScheduledActivation(STORE_ID);

        assertThat(decision.activationAllowed()).isFalse();
        assertThat(decision.timeZoneId()).isEqualTo("Asia/Seoul");
    }

    @Test
    void updateValidatesEffectiveCatalogCombinationAndChangesStore() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        StoreUpdateRequest request = new StoreUpdateRequest(
                "새 이름", null, null, null, null, List.of("QUIET"),
                new StoreModesRequest(false, true, false), null);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));
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
    void replayedCreateSkipsMutableCatalogValidationAndSave() {
        given(idempotencyExecutor.execute(any(), any()))
                .willReturn(storedOutcome(201));

        StoreCommandResult result = storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest());

        assertThat(result.data().storeId()).isEqualTo(Long.toString(STORE_ID));
        then(operatorAccountService).should().getMe(OPERATOR_ID);
        then(catalogPolicy).shouldHaveNoInteractions();
        then(storeRepository).shouldHaveNoInteractions();
    }

    @Test
    void replayedUpdateRechecksOwnershipButSkipsMutableCatalogValidationAndSave() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        given(idempotencyExecutor.execute(any(), any()))
                .willReturn(storedOutcome(200));

        StoreCommandResult result = storeService.update(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                new StoreUpdateRequest(
                        "재생에서는 적용하지 않음",
                        null, null, null, null, null, null, null));

        assertThat(result.data().storeId()).isEqualTo(Long.toString(STORE_ID));
        then(operatorAccountService).should().getMe(OPERATOR_ID);
        then(storeRepository).should().findOperatorAccountIdById(STORE_ID);
        then(storeRepository).shouldHaveNoMoreInteractions();
        then(catalogPolicy).shouldHaveNoInteractions();
        assertThat(store.getName()).isEqualTo("미리윰");
    }

    @Test
    void reusedCreateKeyRejectsBeforeMutableCatalogValidation() {
        ServiceException conflict =
                new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        given(idempotencyExecutor.execute(any(), any())).willThrow(conflict);

        assertThatThrownBy(() -> storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest()))
                .isSameAs(conflict);

        then(operatorAccountService).should().getMe(OPERATOR_ID);
        then(catalogPolicy).shouldHaveNoInteractions();
        then(storeRepository).shouldHaveNoInteractions();
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

    private IdempotentOutcome storedOutcome(int httpStatus) {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        return new IdempotentOutcome(
                true,
                httpStatus,
                "SUCCESS",
                "STORE",
                Long.toString(STORE_ID),
                objectMapper.valueToTree(ManagedStoreResponse.from(store)));
    }

    private StoreCreateRequest validCreateRequest() {
        return new StoreCreateRequest(
                "1234567890",
                BusinessType.CAFE,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "Asia/Seoul",
                "CAFE_BAKERY",
                List.of("DATE"),
                new StoreModesRequest(true, true, true),
                true,
                true);
    }

    private Store storeOwnedBy(long operatorId) {
        return storeOwnedBy(operatorId, BusinessType.CAFE, true, true);
    }

    private Store storeOwnedBy(
            long operatorId,
            BusinessType businessType,
            boolean menuHoldEnabled,
            boolean pickupEnabled
    ) {
        return Store.create(
                operatorId,
                "1234567890",
                businessType,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                menuHoldEnabled,
                pickupEnabled,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
    }

    private Store transactionStore() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        return store;
    }

    private Menu menu(long storeId, boolean holdAllowed, boolean pickupAllowed) {
        Menu menu = Menu.create(
                storeId,
                menuContent(holdAllowed, pickupAllowed),
                OPERATOR_ID,
                FIXED_CLOCK.instant());
        ReflectionTestUtils.setField(menu, "id", MENU_ID);
        return menu;
    }

    private Menu publishedMenu(boolean holdAllowed, boolean pickupAllowed) {
        Menu menu = menu(STORE_ID, holdAllowed, pickupAllowed);
        menu.publish(FIXED_CLOCK.instant().plusSeconds(1));
        return menu;
    }

    private MenuContent menuContent(boolean holdAllowed, boolean pickupAllowed) {
        return new MenuContent(
                "아메리카노",
                "설명",
                5_000,
                false,
                "COFFEE",
                List.of(),
                List.of(),
                holdAllowed,
                pickupAllowed,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.NOT_APPLICABLE,
                List.of(),
                false);
    }

    private void stubTransactionStoreAndMenu(Store store, Menu menu) {
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));
        given(menuRepository.findByIdForUpdate(MENU_ID))
                .willReturn(Optional.of(menu));
    }

    private void assertMenuStateConflict(ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT);
    }
}
