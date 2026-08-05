package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class MenuHoldSnapshotQueryServiceTest {

    @Mock MenuHoldRepository menuHoldRepository;

    @Test
    void exposesOnlyTheReservationMenuSnapshotFields() {
        assertThat(MenuHoldItemResult.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("menuId", "menuName", "unitPrice", "quantity");
        assertThat(MenuHoldItemResult.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(long.class, String.class, long.class, int.class);
    }

    @Test
    void declaresThePublicQueryAsReadOnly() throws Exception {
        Transactional transactional = MenuHoldSnapshotQueryService.class
                .getMethod("findByReservationId", long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    void returnsAnImmutableCopyOfTheRepositoryProjection() {
        List<MenuHoldItemResult> repositoryResult = new ArrayList<>(List.of(
                new MenuHoldItemResult(11L, "아메리카노", 5_000L, 2)));
        given(menuHoldRepository.findItemSnapshotsByReservationId(7L))
                .willReturn(repositoryResult);
        MenuHoldSnapshotQueryService service =
                new MenuHoldSnapshotQueryService(menuHoldRepository);

        List<MenuHoldItemResult> result = service.findByReservationId(7L);
        repositoryResult.clear();

        assertThat(result).containsExactly(
                new MenuHoldItemResult(11L, "아메리카노", 5_000L, 2));
        assertThatThrownBy(() -> result.add(
                new MenuHoldItemResult(12L, "카페라테", 6_000L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(menuHoldRepository).findItemSnapshotsByReservationId(7L);
    }

    @Test
    void returnsAnEmptyImmutableListWhenTheReservationHasNoMenuHold() {
        given(menuHoldRepository.findItemSnapshotsByReservationId(8L)).willReturn(List.of());
        MenuHoldSnapshotQueryService service =
                new MenuHoldSnapshotQueryService(menuHoldRepository);

        List<MenuHoldItemResult> result = service.findByReservationId(8L);

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> result.add(
                new MenuHoldItemResult(12L, "카페라테", 6_000L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
