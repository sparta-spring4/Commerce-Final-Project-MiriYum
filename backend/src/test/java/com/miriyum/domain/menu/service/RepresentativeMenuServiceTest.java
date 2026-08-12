package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuReplaceRequest;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuSettingResponse;
import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.MenuVersion;
import com.miriyum.domain.menu.entity.RepresentativeMenuSetting;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;
import com.miriyum.domain.menu.repository.MenuRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuAuditRepository;
import com.miriyum.domain.menu.repository.RepresentativeMenuSettingRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class RepresentativeMenuServiceTest {

    private static final long OPERATOR_ID = 7L;
    private static final long STORE_ID = 3L;
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Mock
    private StoreService storeService;

    @Mock
    private RepresentativeMenuSettingRepository settingRepository;

    @Mock
    private RepresentativeMenuAuditRepository auditRepository;

    @Mock
    private MenuRepository menuRepository;

    @Mock
    private MenuDatabaseClock databaseClock;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private ObjectMapper objectMapper;
    private RepresentativeMenuService service;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        service = new RepresentativeMenuService(
                storeService, settingRepository, auditRepository, menuRepository,
                databaseClock, idempotencyExecutor, objectMapper);
        lenient().when(databaseClock.now()).thenReturn(NOW);
        lenient().when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<Object>> work = invocation.getArgument(1);
            BusinessResult<Object> result = work.get();
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(),
                    objectMapper.valueToTree(result.data()));
        });
    }

    @Test
    void replacesThreeMenusInRequestOrderAndAllowsSoldOut() {
        RepresentativeMenuSetting setting = emptySetting();
        doReturn(List.of(
                        publishedMenu(11L, MenuSellingStatus.SELLING),
                        publishedMenu(12L, MenuSellingStatus.SOLD_OUT),
                        publishedMenu(13L, MenuSellingStatus.SELLING)))
                .when(menuRepository).findAllByIdForUpdate(List.of(11L, 12L, 13L));

        var result = service.replace(OPERATOR_ID, STORE_ID, key(),
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("13", "11", "12")));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().version()).isEqualTo(1L);
        assertThat(result.data().items())
                .extracting(item -> item.menuId())
                .containsExactly("13", "11", "12");
        assertThat(result.data().items())
                .extracting(item -> item.displayOrder())
                .containsExactly(1, 2, 3);
        assertThat(setting.orderedMenuIds()).containsExactly(13L, 11L, 12L);
        verify(auditRepository).save(any());
    }

    @Test
    void acceptsExactlyFiveMenus() {
        emptySetting();
        doReturn(List.of(
                        publishedMenu(1L, MenuSellingStatus.SELLING),
                        publishedMenu(2L, MenuSellingStatus.SELLING),
                        publishedMenu(3L, MenuSellingStatus.SELLING),
                        publishedMenu(4L, MenuSellingStatus.SELLING),
                        publishedMenu(5L, MenuSellingStatus.SELLING)))
                .when(menuRepository)
                .findAllByIdForUpdate(List.of(1L, 2L, 3L, 4L, 5L));

        var result = service.replace(OPERATOR_ID, STORE_ID, key(),
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("5", "4", "3", "2", "1")));

        assertThat(result.data().items()).hasSize(5);
    }

    @Test
    void rejectsInvalidCardinalityAndDuplicateIds() {
        assertError(CommonErrorCode.VALIDATION_FAILED,
                new RepresentativeMenuReplaceRequest(0L, List.of("1", "2")));
        assertError(CommonErrorCode.VALIDATION_FAILED,
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("1", "2", "3", "4", "5", "6")));
        assertError(CommonErrorCode.VALIDATION_FAILED,
                new RepresentativeMenuReplaceRequest(0L, List.of("1", "1", "2")));
        assertError(CommonErrorCode.VALIDATION_FAILED,
                new RepresentativeMenuReplaceRequest(0L, List.of("01", "1", "2")));
        verify(menuRepository, never()).findAllByIdForUpdate(any());
    }

    @Test
    void rejectsStaleExpectedVersion() {
        RepresentativeMenuSetting setting = RepresentativeMenuSetting.create(STORE_ID);
        setting.replace(List.of(1L, 2L, 3L));
        given(settingRepository.findByStoreIdForUpdate(STORE_ID))
                .willReturn(Optional.of(setting));

        assertError(CommonErrorCode.CONCURRENT_MODIFICATION,
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("11", "12", "13")));
        verify(menuRepository, never()).findAllByIdForUpdate(any());
    }

    @Test
    void returnsStoredResultWithoutRepeatingBusinessWorkOnIdempotentReplay() {
        RepresentativeMenuSettingResponse stored =
                RepresentativeMenuSettingResponse.unconfigured();
        doReturn(new IdempotentOutcome(
                true, 200, "SUCCESS", "REPRESENTATIVE_MENU_SETTING",
                String.valueOf(STORE_ID), objectMapper.valueToTree(stored)))
                .when(idempotencyExecutor).execute(any(), any());

        var result = service.replace(OPERATOR_ID, STORE_ID, key(),
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("11", "12", "13")));

        assertThat(result.data()).isEqualTo(stored);
        verify(settingRepository, never()).ensureExists(anyLong());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void rejectsMissingOrForeignMenu() {
        emptySetting();
        doReturn(List.of(
                        publishedMenu(11L, MenuSellingStatus.SELLING),
                        publishedMenu(12L, MenuSellingStatus.SELLING)))
                .when(menuRepository).findAllByIdForUpdate(List.of(11L, 12L, 13L));

        assertError(StoreErrorCode.MENU_NOT_FOUND,
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("11", "12", "13")));

        Menu foreign = publishedMenu(23L, MenuSellingStatus.SELLING);
        given(foreign.getStoreId()).willReturn(999L);
        doReturn(List.of(
                        publishedMenu(21L, MenuSellingStatus.SELLING),
                        publishedMenu(22L, MenuSellingStatus.SELLING), foreign))
                .when(menuRepository).findAllByIdForUpdate(List.of(21L, 22L, 23L));
        assertError(StoreErrorCode.MENU_NOT_FOUND,
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("21", "22", "23")));
    }

    @Test
    void rejectsHiddenPausedRetiredAndUnpublishedMenus() {
        assertIneligible(menu(11L, MenuVisibility.HIDDEN,
                MenuSellingStatus.SELLING, false, 1));
        assertIneligible(menu(11L, MenuVisibility.VISIBLE,
                MenuSellingStatus.PAUSED, false, 1));
        assertIneligible(menu(11L, MenuVisibility.VISIBLE,
                MenuSellingStatus.SELLING, true, null));
        assertIneligible(menu(11L, MenuVisibility.VISIBLE,
                MenuSellingStatus.SELLING, false, null));
    }

    private RepresentativeMenuSetting emptySetting() {
        RepresentativeMenuSetting setting = RepresentativeMenuSetting.create(STORE_ID);
        given(settingRepository.findByStoreIdForUpdate(STORE_ID))
                .willReturn(Optional.of(setting));
        return setting;
    }

    private void assertIneligible(Menu invalid) {
        emptySetting();
        doReturn(List.of(
                        invalid,
                        publishedMenu(12L, MenuSellingStatus.SELLING),
                        publishedMenu(13L, MenuSellingStatus.SELLING)))
                .when(menuRepository).findAllByIdForUpdate(List.of(11L, 12L, 13L));
        assertError(StoreErrorCode.MENU_STATE_CONFLICT,
                new RepresentativeMenuReplaceRequest(
                        0L, List.of("11", "12", "13")));
    }

    private void assertError(
            Object expectedErrorCode,
            RepresentativeMenuReplaceRequest request
    ) {
        assertThatThrownBy(() -> service.replace(
                OPERATOR_ID, STORE_ID, key(), request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(expectedErrorCode);
    }

    private Menu publishedMenu(long id, MenuSellingStatus sellingStatus) {
        return menu(id, MenuVisibility.VISIBLE, sellingStatus, false, 1);
    }

    private Menu menu(
            long id,
            MenuVisibility visibility,
            MenuSellingStatus sellingStatus,
            boolean retired,
        Integer publishedVersion
    ) {
        Menu menu = mock(Menu.class);
        lenient().when(menu.getId()).thenReturn(id);
        lenient().when(menu.getStoreId()).thenReturn(STORE_ID);
        lenient().when(menu.getVisibility()).thenReturn(visibility);
        lenient().when(menu.getSellingStatus()).thenReturn(sellingStatus);
        lenient().when(menu.isRetired()).thenReturn(retired);
        lenient().when(menu.getPublishedVersionNumber()).thenReturn(publishedVersion);
        if (publishedVersion != null) {
            MenuVersion version = mock(MenuVersion.class);
            lenient().when(version.getVersionNumber()).thenReturn(publishedVersion);
            lenient().when(version.getStatus()).thenReturn(MenuVersionStatus.PUBLISHED);
            lenient().when(version.getName()).thenReturn("menu-" + id);
            lenient().when(version.getPrice()).thenReturn((int) id * 100);
            lenient().when(menu.getVersions()).thenReturn(List.of(version));
        }
        return menu;
    }

    private IdempotencyKey key() {
        return IdempotencyKey.parse("123e4567-e89b-42d3-a456-426614174000");
    }
}
