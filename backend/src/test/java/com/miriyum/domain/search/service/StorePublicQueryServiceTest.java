package com.miriyum.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuItem;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.service.RepresentativeMenuQueryService;
import com.miriyum.domain.schedule.dto.contract.PublicStoreSchedules;
import com.miriyum.domain.schedule.service.StoreScheduleQueryService;
import com.miriyum.domain.search.dto.publicapi.ReservationAvailability;
import com.miriyum.domain.search.dto.publicapi.PublicMenu;
import com.miriyum.domain.search.model.ReservationSearchCondition;
import com.miriyum.domain.search.repository.PublicStoreSnapshot;
import com.miriyum.domain.search.repository.StorePublicReadRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStorageMetadata;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.FileStorageStatus;
import com.miriyum.global.storage.FileStorageVisibility;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorePublicQueryServiceTest {

    @Mock StorePublicReadRepository publicReadRepository;
    @Mock StoreScheduleQueryService scheduleQueryService;
    @Mock ReservationService reservationService;
    @Mock RepresentativeMenuQueryService representativeMenuQueryService;
    @Mock ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;
    @Mock FileStorageFacade fileStorageFacade;

    private StorePublicQueryService service;

    @BeforeEach
    void setUp() {
        service = new StorePublicQueryService(
                publicReadRepository,
                scheduleQueryService,
                reservationService,
                representativeMenuQueryService,
                fileStorageFacadeProvider);
    }

    @Test
    void publicMenusExposeOnlyCurrentPublishedVisibleNonRetiredMenus() {
        PublicMenu visible = publicMenu(11L);
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of(visible));

        var result = service.getMenus(7L);

        assertThat(result).singleElement().satisfies(menu -> {
            assertThat(menu.menuId()).isEqualTo("11");
            assertThat(menu.name()).isEqualTo("아메리카노");
            assertThat(menu.saleStatus()).isEqualTo(MenuSellingStatus.SELLING);
        });
    }

    @Test
    void publicMenusAttachOnlyConfirmedPublicMenuImageUrls() {
        PublicMenu menu = publicMenu(11L);
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of(menu));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findConfirmedPublicUrls(
                List.of(new FileStorageOwner("MENU", 11L)), FileStoragePurpose.MENU_IMAGE))
                .willReturn(Map.of(
                        new FileStorageOwner("MENU", 11L), "/api/v1/public-files/menu-image-id"));

        List<PublicMenu> result = service.getMenus(7L);

        assertThat(result).singleElement().satisfies(found ->
                assertThat(found.imageUrl()).isEqualTo("/api/v1/public-files/menu-image-id"));
    }

    @Test
    void publicMenusKeepImageUrlNullWhenStorageIsNotConfigured() {
        PublicMenu menu = publicMenu(11L);
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of(menu));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(null);

        List<PublicMenu> result = service.getMenus(7L);

        assertThat(result).singleElement().satisfies(found -> assertThat(found.imageUrl()).isNull());
    }

    @Test
    void publicImagesExposeConfirmedStoreImagesOnlyAfterPublicStoreCheck() {
        FileStorageOwner owner = new FileStorageOwner("STORE", 7L);
        UUID imageId = UUID.fromString("d2719d4a-6174-4f23-ae57-18b7e4eeab40");
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findPublicMetadata(
                owner,
                FileStoragePurpose.STORE_IMAGE,
                List.of(FileStorageStatus.CONFIRMED)))
                .willReturn(List.of(new FileStorageMetadata(
                        imageId,
                        owner,
                        FileStoragePurpose.STORE_IMAGE,
                        "public/stores/7/store.webp",
                        "image/webp",
                        123L,
                        "a".repeat(64),
                        FileStorageVisibility.PUBLIC,
                        FileStorageStatus.CONFIRMED,
                        "PUBLIC_STORE_IMAGE",
                        Instant.parse("2026-08-21T00:00:00Z"),
                        null)));

        var result = service.getImages(7L);

        assertThat(result).singleElement().satisfies(image -> {
            assertThat(image.imageId()).isEqualTo(imageId);
            assertThat(image.url()).isEqualTo("/api/v1/public-files/" + imageId);
        });
    }

    @Test
    void publicImagesExposeOnlyTheDeterministicRepresentativeWhenMultipleImagesAreConfirmed() {
        FileStorageOwner owner = new FileStorageOwner("STORE", 7L);
        UUID earlierImageId = UUID.fromString("d2719d4a-6174-4f23-ae57-18b7e4eeab40");
        UUID laterImageId = UUID.fromString("e3719d4a-6174-4f23-ae57-18b7e4eeab40");
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findPublicMetadata(
                owner,
                FileStoragePurpose.STORE_IMAGE,
                List.of(FileStorageStatus.CONFIRMED)))
                .willReturn(List.of(
                        confirmedStoreImage(owner, laterImageId, Instant.parse("2026-08-22T00:00:00Z")),
                        confirmedStoreImage(owner, earlierImageId, Instant.parse("2026-08-21T00:00:00Z"))));

        var result = service.getImages(7L);

        assertThat(result).singleElement().satisfies(image ->
                assertThat(image.imageId()).isEqualTo(earlierImageId));
    }

    @Test
    void publicImagesBreakRepresentativeTimestampTiesByImageId() {
        FileStorageOwner owner = new FileStorageOwner("STORE", 7L);
        Instant createdAt = Instant.parse("2026-08-21T00:00:00Z");
        UUID firstImageId = UUID.fromString("d2719d4a-6174-4f23-ae57-18b7e4eeab40");
        UUID secondImageId = UUID.fromString("e3719d4a-6174-4f23-ae57-18b7e4eeab40");
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findPublicMetadata(
                owner,
                FileStoragePurpose.STORE_IMAGE,
                List.of(FileStorageStatus.CONFIRMED)))
                .willReturn(List.of(
                        confirmedStoreImage(owner, secondImageId, createdAt),
                        confirmedStoreImage(owner, firstImageId, createdAt)));

        var result = service.getImages(7L);

        assertThat(result).singleElement().satisfies(image ->
                assertThat(image.imageId()).isEqualTo(firstImageId));
    }

    @Test
    void publicMenusTreatStoreClosedDuringProjectionAsNotFound() {
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.empty());
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of());

        assertThatThrownBy(() -> service.getMenus(7L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
        var order = inOrder(publicReadRepository);
        order.verify(publicReadRepository).findPublicMenus(7L);
        order.verify(publicReadRepository).findPublicStore(7L);
    }

    @Test
    void detailUsesReservationBatchAndFailsClosedOnMismatchedStoreId() {
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of());
        given(scheduleQueryService.getPublicSchedules(7L))
                .willReturn(PublicStoreSchedules.empty());
        given(representativeMenuQueryService.getCurrent(7L))
                .willReturn(RepresentativeMenuSnapshot.unconfigured(7L));
        given(reservationService.getAvailabilities(org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new ReservationAvailabilityResult(
                        8L, ReservationAvailabilityStatus.AVAILABLE)));

        var result = service.getDetail(
                7L,
                new ReservationSearchCondition(
                        LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 2),
                true);

        assertThat(result.reservationAvailability())
                .isEqualTo(ReservationAvailability.UNAVAILABLE);
        assertThat(result.operatingHours()).isEmpty();
        assertThat(result.reservationTimeSlots()).isEmpty();
        var order = inOrder(reservationService, publicReadRepository);
        order.verify(reservationService).getAvailabilities(
                org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any());
        order.verify(publicReadRepository).findPublicMenus(7L);
        order.verify(publicReadRepository).findPublicStore(7L);
    }

    @Test
    void detailReconcilesAvailableWhenFinalStoreIsTemporarilyClosed() {
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(
                publicStore(7L, OperationStatus.TEMPORARILY_CLOSED, true)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of());
        given(scheduleQueryService.getPublicSchedules(7L))
                .willReturn(PublicStoreSchedules.empty());
        given(representativeMenuQueryService.getCurrent(7L))
                .willReturn(RepresentativeMenuSnapshot.unconfigured(7L));
        given(reservationService.getAvailabilities(
                org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new ReservationAvailabilityResult(
                        7L, ReservationAvailabilityStatus.AVAILABLE)));

        var result = service.getDetail(
                7L,
                new ReservationSearchCondition(
                        LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 2),
                false);

        assertThat(result.operationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);
        assertThat(result.modes().reservationEnabled()).isTrue();
        assertThat(result.reservationAvailability())
                .isEqualTo(ReservationAvailability.UNAVAILABLE);
    }

    @Test
    void detailReconcilesAvailableWhenFinalReservationModeIsDisabled() {
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(
                publicStore(7L, OperationStatus.OPEN, false)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of());
        given(scheduleQueryService.getPublicSchedules(7L))
                .willReturn(PublicStoreSchedules.empty());
        given(representativeMenuQueryService.getCurrent(7L))
                .willReturn(RepresentativeMenuSnapshot.unconfigured(7L));
        given(reservationService.getAvailabilities(
                org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new ReservationAvailabilityResult(
                        7L, ReservationAvailabilityStatus.AVAILABLE)));

        var result = service.getDetail(
                7L,
                new ReservationSearchCondition(
                        LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 2),
                false);

        assertThat(result.operationStatus()).isEqualTo(OperationStatus.OPEN);
        assertThat(result.modes().reservationEnabled()).isFalse();
        assertThat(result.reservationAvailability())
                .isEqualTo(ReservationAvailability.UNAVAILABLE);
    }

    @Test
    void detailUsesCurrentRepresentativeSettingOrderAndKeepsSoldOut() {
        PublicMenu eleven = publicMenu(11L);
        PublicMenu twelve = publicMenu(12L);
        PublicMenu thirteen = new PublicMenu(
                "13", "sold out", "", 7_000, true, "COFFEE",
                List.of(), List.of(), false, false, MenuSellingStatus.SOLD_OUT);
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L))
                .willReturn(List.of(eleven, twelve, thirteen));
        given(scheduleQueryService.getPublicSchedules(7L))
                .willReturn(PublicStoreSchedules.empty());
        given(representativeMenuQueryService.getCurrent(7L)).willReturn(
                new RepresentativeMenuSnapshot(
                        "7",
                        8L,
                        RepresentativeMenuSettingStatus.CONFIGURED,
                        List.of(
                                representative(13L, 1, MenuSellingStatus.SOLD_OUT),
                                representative(11L, 2, MenuSellingStatus.SELLING))));

        var result = service.getDetail(7L, null, false);

        assertThat(result.representativeMenus())
                .extracting(PublicMenu::menuId)
                .containsExactly("13", "11");
        assertThat(result.representativeMenus().getFirst().saleStatus())
                .isEqualTo(MenuSellingStatus.SOLD_OUT);
    }

    @Test
    void detailKeepsTheSamePublicImageUrlForRepresentativeMenus() {
        PublicMenu menu = publicMenu(11L);
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of(menu));
        given(scheduleQueryService.getPublicSchedules(7L))
                .willReturn(PublicStoreSchedules.empty());
        given(representativeMenuQueryService.getCurrent(7L)).willReturn(
                new RepresentativeMenuSnapshot(
                        "7", 1L, RepresentativeMenuSettingStatus.CONFIGURED,
                        List.of(representative(11L, 1, MenuSellingStatus.SELLING))));
        given(fileStorageFacadeProvider.getIfAvailable()).willReturn(fileStorageFacade);
        given(fileStorageFacade.findConfirmedPublicUrls(
                List.of(new FileStorageOwner("MENU", 11L)), FileStoragePurpose.MENU_IMAGE))
                .willReturn(Map.of(
                        new FileStorageOwner("MENU", 11L), "/api/v1/public-files/menu-image-id"));

        var result = service.getDetail(7L, null, false);

        assertThat(result.representativeMenus()).singleElement().satisfies(found ->
                assertThat(found.imageUrl()).isEqualTo("/api/v1/public-files/menu-image-id"));
    }

    private static PublicStoreSnapshot publicStore(long id) {
        return publicStore(id, OperationStatus.OPEN, true);
    }

    private static PublicStoreSnapshot publicStore(
            long id,
            OperationStatus operationStatus,
            boolean reservationEnabled
    ) {
        return new PublicStoreSnapshot(
                id, "미리윰", "", Region.SEOUL, "서울 중구", "Asia/Seoul",
                "CAFE_BAKERY", List.of("DATE"), operationStatus,
                reservationEnabled, true, true);
    }

    private static PublicMenu publicMenu(long id) {
        return new PublicMenu(
                Long.toString(id), "아메리카노", "", 4500, true, "COFFEE",
                List.of(), List.of(), false, false, MenuSellingStatus.SELLING);
    }

    private static FileStorageMetadata confirmedStoreImage(
            FileStorageOwner owner,
            UUID imageId,
            Instant createdAt
    ) {
        return new FileStorageMetadata(
                imageId,
                owner,
                FileStoragePurpose.STORE_IMAGE,
                "public/stores/7/" + imageId + ".webp",
                "image/webp",
                123L,
                "a".repeat(64),
                FileStorageVisibility.PUBLIC,
                FileStorageStatus.CONFIRMED,
                "PUBLIC_STORE_IMAGE",
                createdAt,
                null);
    }

    private static RepresentativeMenuItem representative(
            long id,
            int displayOrder,
            MenuSellingStatus status
    ) {
        return new RepresentativeMenuItem(
                Long.toString(id), displayOrder, 1, "menu", 1_000, status);
    }
}
