package com.miriyum.domain.reservation.service;

import static com.miriyum.domain.menu.enums.MenuSellingStatus.SELLING;
import static com.miriyum.domain.menu.enums.MenuSellingStatus.SOLD_OUT;
import static com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus.CONFIGURED;
import static com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus.REQUIRES_ATTENTION;
import static com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus.UNCONFIGURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.miriyum.domain.menu.dto.contract.RepresentativeMenuItem;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy.Status;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationDepositCalculatorTest {

    private final ReservationDepositCalculator calculator =
            new ReservationDepositCalculator();

    @Test
    void calculatesWithoutIntermediateTruncationAndPreservesTheInputs() {
        StoreReservationDepositPolicy policy = enabledPolicy(7L, 10, 91L);
        RepresentativeMenuSnapshot menus = configuredMenus(
                7L,
                13L,
                List.of(
                        item("101", 1, 4, "첫 메뉴", 30_000, SELLING),
                        item("102", 2, 8, "품절 메뉴", 30_010, SOLD_OUT)
                )
        );

        ReservationDepositCalculator.Calculation result =
                calculator.calculate(policy, menus, 1);

        assertThat(result.amountMinor()).isEqualTo(4_000L);
        assertThat(result.currency()).isEqualTo("KRW");
        assertThat(result.storePolicyVersion()).isEqualTo(91L);
        assertThat(result.ratePercent()).isEqualTo(10);
        assertThat(result.algorithmVersion()).isEqualTo(1L);
        assertThat(result.partySize()).isEqualTo(1);
        assertThat(result.representativeMenuVersion()).isEqualTo(13L);
        assertThat(result.representativeMenuPriceTotal()).isEqualTo(60_010L);
        assertThat(result.representativeMenuCount()).isEqualTo(2);
        assertThat(result.items())
                .extracting(
                        ReservationDepositCalculator.ItemSnapshot::menuId,
                        ReservationDepositCalculator.ItemSnapshot::publishedVersionNumber,
                        ReservationDepositCalculator.ItemSnapshot::price)
                .containsExactly(
                        tuple("101", 4, 30_000),
                        tuple("102", 8, 30_010));
    }

    @Test
    void keepsAnExactThousandWonBoundaryUnchanged() {
        ReservationDepositCalculator.Calculation result = calculator.calculate(
                enabledPolicy(7L, 10, 91L),
                configuredMenus(
                        7L,
                        13L,
                        List.of(
                                item("101", 1, 4, "첫 메뉴", 30_000, SELLING),
                                item("102", 2, 8, "둘째 메뉴", 30_000, SELLING)
                        )),
                1
        );

        assertThat(result.amountMinor()).isEqualTo(3_000L);
    }

    @ParameterizedTest
    @EnumSource(value = RepresentativeMenuSettingStatus.class,
            names = {"UNCONFIGURED", "REQUIRES_ATTENTION"})
    void rejectsAnUnusableRepresentativeMenuSetting(
            RepresentativeMenuSettingStatus status
    ) {
        RepresentativeMenuSnapshot menus = new RepresentativeMenuSnapshot(
                "7",
                status == UNCONFIGURED ? 0L : 13L,
                status,
                List.of()
        );

        assertThatThrownBy(() -> calculator.calculate(
                enabledPolicy(7L, 10, 91L), menus, 2))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT));
    }

    @Test
    void rejectsAConfiguredSettingWithoutAValidItem() {
        RepresentativeMenuSnapshot menus = configuredMenus(7L, 13L, List.of());

        assertThatThrownBy(() -> calculator.calculate(
                enabledPolicy(7L, 10, 91L), menus, 2))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT));
    }

    private static StoreReservationDepositPolicy enabledPolicy(
            long storeId,
            int ratePercent,
            long policyVersion
    ) {
        return new StoreReservationDepositPolicy(
                storeId,
                Status.ENABLED,
                OptionalInt.of(ratePercent),
                OptionalLong.of(policyVersion)
        );
    }

    private static RepresentativeMenuSnapshot configuredMenus(
            long storeId,
            long version,
            List<RepresentativeMenuItem> items
    ) {
        return new RepresentativeMenuSnapshot(
                String.valueOf(storeId), version, CONFIGURED, items);
    }

    private static RepresentativeMenuItem item(
            String menuId,
            int displayOrder,
            int publishedVersionNumber,
            String name,
            int price,
            com.miriyum.domain.menu.enums.MenuSellingStatus status
    ) {
        return new RepresentativeMenuItem(
                menuId, displayOrder, publishedVersionNumber, name, price, status);
    }
}
