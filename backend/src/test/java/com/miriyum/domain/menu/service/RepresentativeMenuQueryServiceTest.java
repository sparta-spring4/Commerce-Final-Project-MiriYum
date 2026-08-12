package com.miriyum.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.repository.MenuRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepresentativeMenuQueryServiceTest {

    @Mock
    private MenuRepository menuRepository;

    @Test
    void returnsStoreBoundVersionedSnapshotFromOneProjectionQuery() {
        MenuRepository.RepresentativeMenuRow first = row(
                7L, 4L, "CONFIGURED", 13L, 1,
                2, "latte", 6_000, "SOLD_OUT");
        MenuRepository.RepresentativeMenuRow second = row(
                7L, 4L, "CONFIGURED", 11L, 2,
                1, "americano", 5_000, "SELLING");
        given(menuRepository.findRepresentativeMenuRows(7L))
                .willReturn(List.of(first, second));
        RepresentativeMenuQueryService service =
                new RepresentativeMenuQueryService(menuRepository);

        var snapshot = service.getCurrent(7L);

        assertThat(snapshot.storeId()).isEqualTo("7");
        assertThat(snapshot.version()).isEqualTo(4L);
        assertThat(snapshot.items()).extracting(item -> item.menuId())
                .containsExactly("13", "11");
        assertThat(snapshot.items().getFirst().sellingStatus())
                .isEqualTo(MenuSellingStatus.SOLD_OUT);
        verify(menuRepository).findRepresentativeMenuRows(7L);
        verifyNoMoreInteractions(menuRepository);
    }

    @Test
    void unconfiguredSnapshotStillCarriesStoreIdentity() {
        given(menuRepository.findRepresentativeMenuRows(7L)).willReturn(List.of());
        RepresentativeMenuQueryService service =
                new RepresentativeMenuQueryService(menuRepository);

        var snapshot = service.getCurrent(7L);

        assertThat(snapshot.storeId()).isEqualTo("7");
        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.items()).isEmpty();
    }

    private MenuRepository.RepresentativeMenuRow row(
            long storeId,
            long version,
            String status,
            Long menuId,
            Integer displayOrder,
            Integer publishedVersionNumber,
            String name,
            Integer price,
            String sellingStatus
    ) {
        MenuRepository.RepresentativeMenuRow row =
                mock(MenuRepository.RepresentativeMenuRow.class);
        lenient().when(row.getStoreId()).thenReturn(storeId);
        lenient().when(row.getVersion()).thenReturn(version);
        lenient().when(row.getStatus()).thenReturn(status);
        given(row.getMenuId()).willReturn(menuId);
        given(row.getDisplayOrder()).willReturn(displayOrder);
        given(row.getPublishedVersionNumber()).willReturn(publishedVersionNumber);
        given(row.getName()).willReturn(name);
        given(row.getPrice()).willReturn(price);
        given(row.getSellingStatus()).willReturn(sellingStatus);
        return row;
    }
}
