package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.service.MenuQueryService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MenuInventoryAdminServiceTest {

    @Mock MenuQueryService menuQueryService;
    @Mock MenuInventoryBucketRepository bucketRepository;

    @Test
    void listsCurrentPoliciesOnlyForMenusManagedByTheStore() {
        PageRequest page = PageRequest.of(0, 20);
        MenuInventoryBucket bucket = MenuInventoryBucket.create(
                11L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                "Asia/Seoul", 2L, 5, 3, 1, 1, true);
        ReflectionTestUtils.setField(bucket, "id", 31L);
        given(menuQueryService.list(7L, 3L)).willReturn(List.of(
                new ManagedMenuResponse("11", "3", MenuVisibility.VISIBLE,
                        MenuSellingStatus.SELLING, false, null, null, null)));
        given(bucketRepository.findCurrentPage(
                List.of(11L), LocalDate.of(2026, 8, 10), null, page))
                .willReturn(new PageImpl<>(List.of(bucket), page, 1));

        MenuInventoryAdminService service =
                new MenuInventoryAdminService(menuQueryService, bucketRepository);

        var result = service.list(
                7L, 3L, LocalDate.of(2026, 8, 10), null, page);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().policyVersion()).isEqualTo(2L);
        assertThat(result.getContent().getFirst().menuId()).isEqualTo(11L);
    }
}
