package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.miriyum.domain.store.dto.contract.StoreServiceProfile;
import com.miriyum.domain.store.dto.contract.StoreWaitingReceptionProfile;
import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.dto.storeoperator.StoreUpdateRequest;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.model.StoreGeocodingCandidate;
import com.miriyum.domain.store.model.StoreGeocodingResult;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.model.AllergenDisclosure;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.menu.model.MenuContent;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
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
import java.util.Map;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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

    @Mock
    private StoreGeocodingPort geocodingPort;

    @Mock
    private StoreAdministrationService storeAdministrationService;

    @Mock
    private StoreCommandTransactionExecutor transactionExecutor;

    private ObjectMapper objectMapper;
    private StoreGeocodingValidator geocodingValidator;
    private StoreService storeService;
    private MenuTransactionFacade menuTransactionFacade;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        geocodingValidator = new StoreGeocodingValidator();
        lenient().when(geocodingPort.geocode(any())).thenAnswer(invocation ->
                geocodingResult(invocation.getArgument(0), "서울"));
        lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        storeService = new StoreService(
                operatorAccountService,
                storeRepository,
                catalogPolicy,
                geocodingPort,
                geocodingValidator,
                transactionExecutor,
                idempotencyExecutor,
                objectMapper,
                FIXED_CLOCK,
                storeAdministrationService);
        menuTransactionFacade = new MenuTransactionFacade(
                new StoreTransactionEligibilityService(
                        storeRepository, mock(StoreAdministrationService.class)),
                menuRepository);
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
        assertThat(savedStore.get().getGeocodingStatus())
                .isEqualTo(GeocodingStatus.VERIFIED);
        assertThat(savedStore.get().getAddressVersion()).isEqualTo(1L);
        assertThat(savedStore.get().getGeocodingAddressVersion()).isEqualTo(1L);
        then(operatorAccountService).should().getMe(OPERATOR_ID);
        then(catalogPolicy).should().validate("CAFE_BAKERY", List.of("DATE"));
        then(storeRepository).should().saveAndFlush(any(Store.class));
        InOrder callOrder = inOrder(
                geocodingPort,
                transactionExecutor,
                idempotencyExecutor);
        callOrder.verify(geocodingPort).geocode("서울시 중구");
        callOrder.verify(transactionExecutor).execute(any());
        callOrder.verify(idempotencyExecutor).execute(any(), any());
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
    void returnsServiceProfilesWithoutExposingStoreEntities() {
        Store accepting = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(accepting, "id", STORE_ID);
        Store paused = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(paused, "id", 8L);
        ReflectionTestUtils.setField(
                paused,
                "operationStatus",
                OperationStatus.TEMPORARILY_CLOSED);
        given(storeRepository.findAllById(Set.of(STORE_ID, 8L)))
                .willReturn(List.of(accepting, paused));

        Map<Long, StoreServiceProfile> profiles =
                storeService.getServiceProfiles(Set.of(STORE_ID, 8L));

        assertThat(profiles).containsExactlyInAnyOrderEntriesOf(Map.of(
                STORE_ID,
                new StoreServiceProfile(STORE_ID, "Asia/Seoul", true),
                8L,
                new StoreServiceProfile(8L, "Asia/Seoul", false)));
    }

    @Test
    void returnsWaitingReceptionProfilesForOpenAndUnavailableStores() {
        Store open = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(open, "id", STORE_ID);
        Store temporarilyClosed = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(temporarilyClosed, "id", 8L);
        ReflectionTestUtils.setField(
                temporarilyClosed,
                "operationStatus",
                OperationStatus.TEMPORARILY_CLOSED);
        Store unapproved = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(unapproved, "id", 9L);
        ReflectionTestUtils.setField(unapproved, "verificationStatus", null);
        given(storeRepository.findAllById(Set.of(STORE_ID, 8L, 9L)))
                .willReturn(List.of(open, temporarilyClosed, unapproved));

        Map<Long, StoreWaitingReceptionProfile> profiles =
                storeService.getWaitingReceptionProfiles(Set.of(STORE_ID, 8L, 9L));

        assertThat(profiles).containsExactlyInAnyOrderEntriesOf(Map.of(
                STORE_ID,
                new StoreWaitingReceptionProfile(STORE_ID, "Asia/Seoul", true),
                8L,
                new StoreWaitingReceptionProfile(8L, "Asia/Seoul", false),
                9L,
                new StoreWaitingReceptionProfile(9L, "Asia/Seoul", false)));
    }

    @Test
    void inspectsWaitingReceptionUnderStoreRowLock() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        StoreWaitingReceptionProfile profile =
                storeService.inspectWaitingReceptionForUpdate(STORE_ID);

        assertThat(profile)
                .isEqualTo(new StoreWaitingReceptionProfile(
                        STORE_ID,
                        "Asia/Seoul",
                        true));
        then(storeRepository).should().findByIdForUpdate(STORE_ID);
    }

    @Test
    void lockedWaitingReceptionInspectionFailsClosedForUnavailableStore() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        ReflectionTestUtils.setField(
                store,
                "operationStatus",
                OperationStatus.TEMPORARILY_CLOSED);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        StoreWaitingReceptionProfile profile =
                storeService.inspectWaitingReceptionForUpdate(STORE_ID);

        assertThat(profile.waitingReceptionEligible()).isFalse();
    }

    @Test
    void lockedWaitingReceptionInspectionRejectsMissingStore() {
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.inspectWaitingReceptionForUpdate(STORE_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
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
    void publicDisplayNameLookupReturnsOnlyTheStoreName() {
        Store store = transactionStore();
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store));

        assertThat(storeService.findDisplayName(STORE_ID)).contains("미리윰");
        assertThat(storeService.findDisplayName(0L)).isEmpty();
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
    void menuMutationAuthorityReturnsLockedStore() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(store));

        StoreMenuAuthority authority =
                storeService.requireMenuMutationAuthority(OPERATOR_ID, STORE_ID);

        assertThat(authority).isEqualTo(new StoreMenuAuthority(STORE_ID));
    }

    @Test
    void transactionEligibilityRejectsMissingStoreBeforeLoadingMenu() {
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
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
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
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
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
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
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
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
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_NOT_FOUND);
    }

    @Test
    void transactionEligibilityHidesWrongStoreOwnershipAsMissingMenu() {
        Menu menu = menu(STORE_ID + 1, true, true);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertThatThrownBy(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_NOT_FOUND);
    }

    @Test
    void transactionEligibilityRejectsUnpublishedMenu() {
        stubTransactionStoreAndMenu(transactionStore(), menu(STORE_ID, true, true));

        assertMenuStateConflict(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsHiddenMenu() {
        Menu menu = publishedMenu(true, true);
        menu.changeVisibility(MenuVisibility.HIDDEN);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsPausedMenu() {
        Menu menu = publishedMenu(true, true);
        menu.changeSellingStatus(MenuSellingStatus.PAUSED);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsSoldOutMenu() {
        Menu menu = publishedMenu(true, true);
        menu.changeSellingStatus(MenuSellingStatus.SOLD_OUT);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityRejectsRetiredMenu() {
        Menu menu = publishedMenu(true, true);
        menu.retire(FIXED_CLOCK.instant().plusSeconds(2));
        stubTransactionStoreAndMenu(transactionStore(), menu);

        assertMenuStateConflict(() ->
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID));
    }

    @Test
    void transactionEligibilityReturnsCurrentPublishedVersionAndCapabilities() {
        Menu menu = publishedMenu(true, true);
        menu.appendDraft(menuContent("카페라떼", 6_500, true, true), OPERATOR_ID,
                FIXED_CLOCK.instant().plusSeconds(2));
        menu.publish(FIXED_CLOCK.instant().plusSeconds(3));
        stubTransactionStoreAndMenu(transactionStore(), menu);

        MenuTransactionEligibility result =
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result).isEqualTo(new MenuTransactionEligibility(
                STORE_ID, MENU_ID, 2, "카페라떼", 6_500, true, true));
        InOrder lockOrder = inOrder(storeRepository, menuRepository);
        lockOrder.verify(storeRepository).findByIdForUpdate(STORE_ID);
        lockOrder.verify(menuRepository).findByIdForUpdate(MENU_ID);
        then(operatorAccountService).shouldHaveNoInteractions();
    }

    @Test
    void transactionEligibilityRejectsBlankMenuName() {
        assertThatThrownBy(() -> new MenuTransactionEligibility(
                STORE_ID, MENU_ID, 1, " ", 5_000, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menuName must not be blank");
    }

    @Test
    void transactionEligibilityRejectsNegativeUnitPrice() {
        assertThatThrownBy(() -> new MenuTransactionEligibility(
                STORE_ID, MENU_ID, 1, "아메리카노", -1, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unitPrice must not be negative");
    }

    @Test
    void transactionEligibilityCombinesPublishedVersionCapabilities() {
        Menu menu = publishedMenu(false, false);
        stubTransactionStoreAndMenu(transactionStore(), menu);

        MenuTransactionEligibility result =
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result.menuHoldEligible()).isFalse();
        assertThat(result.pickupEligible()).isFalse();
    }

    @Test
    void transactionEligibilityCombinesStoreModes() {
        Store store = storeOwnedBy(OPERATOR_ID, BusinessType.CAFE, false, false);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        stubTransactionStoreAndMenu(store, publishedMenu(true, true));

        MenuTransactionEligibility result =
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result.menuHoldEligible()).isFalse();
        assertThat(result.pickupEligible()).isFalse();
    }

    @Test
    void transactionEligibilityDisablesMenuHoldWhenReservationModeIsDisabled() {
        Store store = transactionStore();
        ReflectionTestUtils.setField(store, "reservationEnabled", false);
        stubTransactionStoreAndMenu(store, publishedMenu(true, true));

        MenuTransactionEligibility result =
                menuTransactionFacade.requireTransactionEligibility(STORE_ID, MENU_ID);

        assertThat(result.menuHoldEligible()).isFalse();
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
        then(geocodingPort).shouldHaveNoInteractions();
    }

    @Test
    void addressOnlyUpdateGeocodesWithCurrentRegion() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, null, "서울 중구 세종대로 110",
                null, null, null, null);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store));
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));
        given(storeRepository.saveAndFlush(store)).willReturn(store);
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willReturn(geocodingResult("서울 중구 세종대로 110", "서울"));
        runBusinessWorkOnExecute();

        storeService.update(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                request);

        assertThat(store.getRegion()).isEqualTo(Region.SEOUL);
        assertThat(store.getAddress()).isEqualTo("서울 중구 세종대로 110");
        assertThat(store.getAddressVersion()).isEqualTo(2L);
        assertThat(store.getGeocodingStatus()).isEqualTo(GeocodingStatus.VERIFIED);
    }

    @Test
    void regionOnlyUpdateGeocodesCurrentAddressWithRequestedRegion() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, Region.BUSAN, null,
                null, null, null, null);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store));
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));
        given(storeRepository.saveAndFlush(store)).willReturn(store);
        given(geocodingPort.geocode("서울시 중구"))
                .willReturn(geocodingResult("서울시 중구", "부산"));
        runBusinessWorkOnExecute();

        storeService.update(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                request);

        assertThat(store.getRegion()).isEqualTo(Region.BUSAN);
        assertThat(store.getAddress()).isEqualTo("서울시 중구");
        assertThat(store.getAddressVersion()).isEqualTo(2L);
        assertThat(store.getGeocodingStatus()).isEqualTo(GeocodingStatus.VERIFIED);
    }

    @Test
    void staleLocationPreflightIsRejectedBeforeMutation() {
        Store snapshot = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(snapshot, "id", STORE_ID);
        Store locked = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(locked, "id", STORE_ID);
        ReflectionTestUtils.setField(locked, "region", Region.BUSAN);
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, null, "서울 중구 세종대로 110",
                null, null, null, null);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(snapshot));
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(locked));
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willReturn(geocodingResult("서울 중구 세종대로 110", "서울"));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> storeService.update(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);

        assertThat(locked.getAddress()).isEqualTo("서울시 중구");
        assertThat(locked.getAddressVersion()).isEqualTo(1L);
        then(storeRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void bothLocationFieldsRejectInterveningAddressVersionChange() {
        Store snapshot = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(snapshot, "id", STORE_ID);
        Store locked = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(locked, "id", STORE_ID);
        ReflectionTestUtils.setField(locked, "addressVersion", 2L);
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, Region.SEOUL, "서울 중구 세종대로 110",
                null, null, null, null);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        lenient().when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(snapshot));
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(locked));
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willReturn(geocodingResult("서울 중구 세종대로 110", "서울"));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> storeService.update(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);

        assertThat(locked.getAddress()).isEqualTo("서울시 중구");
        assertThat(locked.getAddressVersion()).isEqualTo(2L);
        then(storeRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void explicitAddressRejectsInterveningAddressChangeWithSameRegion() {
        Store snapshot = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(snapshot, "id", STORE_ID);
        Store locked = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(locked, "id", STORE_ID);
        ReflectionTestUtils.setField(locked, "address", "서울 중구 을지로 100");
        ReflectionTestUtils.setField(locked, "addressVersion", 2L);
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, null, "서울 중구 세종대로 110",
                null, null, null, null);
        given(storeRepository.findOperatorAccountIdById(STORE_ID))
                .willReturn(Optional.of(OPERATOR_ID));
        given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(snapshot));
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(locked));
        given(geocodingPort.geocode("서울 중구 세종대로 110"))
                .willReturn(geocodingResult("서울 중구 세종대로 110", "서울"));
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> storeService.update(
                OPERATOR_ID,
                STORE_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);

        assertThat(locked.getAddress()).isEqualTo("서울 중구 을지로 100");
        assertThat(locked.getAddressVersion()).isEqualTo(2L);
        then(storeRepository).should(never()).saveAndFlush(any());
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
    void replayedCreateReturnsStoredSuccessDespiteCurrentProviderFailure() {
        ServiceException providerFailure =
                new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        given(geocodingPort.geocode("서울시 중구")).willThrow(providerFailure);
        given(idempotencyExecutor.execute(any(), any()))
                .willReturn(storedOutcome(201));

        StoreCommandResult result = storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest());

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().storeId()).isEqualTo(Long.toString(STORE_ID));
        then(storeRepository).shouldHaveNoInteractions();
        then(catalogPolicy).shouldHaveNoInteractions();
    }

    @Test
    void replayedCreateAcceptsLegacyPickupEligibilityPayload() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        ObjectNode legacyPayload = objectMapper.valueToTree(ManagedStoreResponse.from(store));
        legacyPayload.remove("geocoding");
        legacyPayload.put("pickupEligibility", "ELIGIBLE");
        given(idempotencyExecutor.execute(any(), any()))
                .willReturn(new IdempotentOutcome(
                        true,
                        201,
                        "SUCCESS",
                        "STORE",
                        Long.toString(STORE_ID),
                        legacyPayload));

        StoreCommandResult result = storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest());

        assertThat(result.data().storeId()).isEqualTo(Long.toString(STORE_ID));
        assertThat(result.data().geocoding().status()).isEqualTo(GeocodingStatus.UNVERIFIED);
        assertThat(result.data().geocoding().addressVersion()).isEqualTo(1L);
        assertThat(result.data().geocoding().latitude()).isNull();
        assertThat(result.data().geocoding().longitude()).isNull();
        then(storeRepository).shouldHaveNoInteractions();
    }

    @Test
    void newCreateProviderFailureDoesNotSaveStore() {
        ServiceException providerFailure =
                new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        given(geocodingPort.geocode("서울시 중구")).willThrow(providerFailure);
        runBusinessWorkOnExecute();

        assertThatThrownBy(() -> storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest()))
                .isSameAs(providerFailure);

        then(storeRepository).shouldHaveNoInteractions();
        then(catalogPolicy).shouldHaveNoInteractions();
        then(transactionExecutor).should().execute(any());
        then(idempotencyExecutor).should().execute(any(), any());
    }

    @Test
    void replayedCreateStillRejectsUnrelatedUnknownPayloadField() {
        Store store = storeOwnedBy(OPERATOR_ID);
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        ObjectNode invalidPayload = objectMapper.valueToTree(ManagedStoreResponse.from(store));
        invalidPayload.put("unexpectedField", true);
        given(idempotencyExecutor.execute(any(), any()))
                .willReturn(new IdempotentOutcome(
                        true,
                        201,
                        "SUCCESS",
                        "STORE",
                        Long.toString(STORE_ID),
                        invalidPayload));

        assertThatThrownBy(() -> storeService.create(
                OPERATOR_ID,
                IdempotencyKey.parse(IDEMPOTENCY_KEY),
                validCreateRequest()))
                .isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException.class);
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

    private StoreGeocodingResult geocodingResult(String address, String region1DepthName) {
        return new StoreGeocodingResult(
                1,
                List.of(new StoreGeocodingCandidate(
                        address,
                        null,
                        region1DepthName,
                        "126.978656700000000",
                        "37.566826000000000")),
                "KAKAO_LOCAL",
                "v2");
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
        return menuContent("아메리카노", 5_000, holdAllowed, pickupAllowed);
    }

    private MenuContent menuContent(
            String name,
            int price,
            boolean holdAllowed,
            boolean pickupAllowed
    ) {
        return new MenuContent(
                name,
                "설명",
                price,
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
