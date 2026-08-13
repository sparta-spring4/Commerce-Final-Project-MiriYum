package com.miriyum.domain.menuhold.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuHoldTest {

    @Test
    void confirmsOneItemPerSelectedMenu() {
        MenuHold hold = MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(new MenuHoldItemSnapshot(
                        40L, 50L, 2L, "아메리카노", 5_000, 3L, 4)));

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(hold.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuId()).isEqualTo(40L);
            assertThat(item.getMenuInventoryBucketId()).isEqualTo(50L);
            assertThat(item.getMenuNameSnapshot()).isEqualTo("아메리카노");
            assertThat(item.getUnitPriceSnapshot()).isEqualTo(5_000);
            assertThat(item.getQuantity()).isEqualTo(4);
        });
    }

    @Test
    void acceptsSameMenuInDifferentInventoryBuckets() {
        MenuHold hold = MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(
                        new MenuHoldItemSnapshot(
                                40L, 50L, 2L, "아메리카노", 5_000, 3L, 4),
                        new MenuHoldItemSnapshot(
                                40L, 51L, 2L, "아메리카노", 5_000, 3L, 1)));

        assertThat(hold.getItems()).hasSize(2);
    }

    @Test
    void rejectsDuplicateInventoryBuckets() {
        MenuHoldItemSnapshot item = new MenuHoldItemSnapshot(
                40L, 50L, 2L, "아메리카노", 5_000, 3L, 4);

        assertThatThrownBy(() -> MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(item, item)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("menu hold items must have unique inventory buckets");
    }

    @Test
    void rejectsInvalidMenuDisplaySnapshot() {
        assertThatThrownBy(() -> new MenuHoldItemSnapshot(
                40L, 50L, 2L, " ", 5_000, 3L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MenuHoldItemSnapshot(
                40L, 50L, 2L, "가".repeat(101), 5_000, 3L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MenuHoldItemSnapshot(
                40L, 50L, 2L, "아메리카노", -1, 3L, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releasesConfirmedHoldAndTreatsRepeatedReleaseAsIdempotent() {
        MenuHold hold = confirmedHold();

        assertThat(hold.release()).isTrue();
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        assertThat(hold.getReservationHoldId()).isNull();
        assertThat(hold.getExpiresAt()).isNull();
        assertThat(hold.release()).isFalse();
    }

    @Test
    void fulfillsConfirmedHoldAndTreatsRepeatedFulfillAsIdempotent() {
        MenuHold hold = confirmedHold();

        assertThat(hold.fulfill()).isTrue();
        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.FULFILLED);
        assertThat(hold.getReservationHoldId()).isNull();
        assertThat(hold.getExpiresAt()).isNull();
        assertThat(hold.fulfill()).isFalse();
    }

    @Test
    void rejectsAConflictingTerminalTransition() {
        MenuHold released = confirmedHold();
        released.release();
        MenuHold fulfilled = confirmedHold();
        fulfilled.fulfill();

        assertThatThrownBy(released::fulfill)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("released menu hold cannot be fulfilled");
        assertThatThrownBy(fulfilled::release)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fulfilled menu hold cannot be released");
    }

    @Test
    void createsTemporaryActiveHoldWithParentAndExactExpiry() {
        Instant expiresAt = Instant.parse("2026-08-10T03:10:00.123456Z");

        MenuHold hold = temporaryHold(expiresAt);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        assertThat(hold.getReservationId()).isNull();
        assertThat(hold.getReservationHoldId()).isEqualTo(11L);
        assertThat(hold.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void marksActiveTemporaryHoldForReconciliationWithoutLosingLinkage() {
        Instant expiresAt = Instant.parse("2026-08-10T03:10:00Z");
        MenuHold hold = temporaryHold(expiresAt);

        hold.requireTemporaryReconciliation();

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.RECONCILIATION_REQUIRED);
        assertThat(hold.getReservationId()).isNull();
        assertThat(hold.getReservationHoldId()).isEqualTo(11L);
        assertThat(hold.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void confirmsActiveTemporaryHoldWithPositiveFinalReservationId() {
        MenuHold hold = temporaryHold();

        hold.confirmTemporary(101L);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(hold.getReservationId()).isEqualTo(101L);
        assertThat(hold.getReservationHoldId()).isEqualTo(11L);
    }

    @Test
    void confirmsReconciliationRequiredTemporaryHold() {
        MenuHold hold = temporaryHold();
        hold.requireTemporaryReconciliation();

        hold.confirmTemporary(102L);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(hold.getReservationId()).isEqualTo(102L);
    }

    @Test
    void rejectsNonPositiveFinalReservationBeforeMutatingTemporaryHold() {
        MenuHold hold = temporaryHold();

        assertThatThrownBy(() -> hold.confirmTemporary(0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        assertThat(hold.getReservationId()).isNull();
        assertThat(hold.getReservationHoldId()).isEqualTo(11L);
    }

    @Test
    void rejectsNonPositiveFinalReservationForReconciliationWithoutMutation() {
        Instant expiresAt = Instant.parse("2026-08-10T03:10:00Z");
        MenuHold hold = temporaryHold(expiresAt);
        hold.requireTemporaryReconciliation();

        assertThatThrownBy(() -> hold.confirmTemporary(0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.RECONCILIATION_REQUIRED);
        assertThat(hold.getReservationId()).isNull();
        assertThat(hold.getReservationHoldId()).isEqualTo(11L);
        assertThat(hold.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void releasesActiveTemporaryHold() {
        MenuHold hold = temporaryHold();

        hold.releaseTemporary();

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        assertThat(hold.getReservationId()).isNull();
    }

    @Test
    void releasesReconciliationRequiredTemporaryHold() {
        MenuHold hold = temporaryHold();
        hold.requireTemporaryReconciliation();

        hold.releaseTemporary();

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.RELEASED);
        assertThat(hold.getReservationId()).isNull();
    }

    @Test
    void expiresActiveTemporaryHold() {
        MenuHold hold = temporaryHold();

        hold.expireTemporary();

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.EXPIRED);
        assertThat(hold.getReservationId()).isNull();
    }

    @Test
    void rejectsTemporaryTransitionsForLegacyHoldBeforeMutation() {
        MenuHold hold = confirmedHold();

        assertThatThrownBy(hold::requireTemporaryReconciliation)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hold.confirmTemporary(101L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(hold::releaseTemporary)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(hold::expireTemporary)
                .isInstanceOf(IllegalStateException.class);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.CONFIRMED);
        assertThat(hold.getReservationId()).isEqualTo(10L);
        assertThat(hold.getReservationHoldId()).isNull();
    }

    @Test
    void rejectsLegacyTransitionsForTemporaryHoldBeforeMutation() {
        MenuHold hold = temporaryHold();

        assertThatThrownBy(hold::release)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(hold::fulfill)
                .isInstanceOf(IllegalStateException.class);

        assertThat(hold.getStatus()).isEqualTo(MenuHoldStatus.ACTIVE);
        assertThat(hold.getReservationId()).isNull();
        assertThat(hold.getReservationHoldId()).isEqualTo(11L);
    }

    private static MenuHold confirmedHold() {
        return MenuHold.confirmed(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1",
                List.of(new MenuHoldItemSnapshot(
                        40L, 50L, 2L, "아메리카노", 5_000, 3L, 4)));
    }

    private static MenuHold temporaryHold() {
        return temporaryHold(Instant.parse("2026-08-10T03:10:00Z"));
    }

    private static MenuHold temporaryHold(Instant expiresAt) {
        return MenuHold.temporaryActive(
                11L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), expiresAt,
                "temporary-operation-1", List.of(new MenuHoldItemSnapshot(
                        40L, 50L, 2L, "아메리카노", 5_000, 3L, 4)));
    }
}
