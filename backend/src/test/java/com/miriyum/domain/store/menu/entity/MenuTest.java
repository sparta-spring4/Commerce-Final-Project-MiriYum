package com.miriyum.domain.store.menu.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.AllergenIngredientCode;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.domain.store.menu.model.OriginDisclosure;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuTest {

    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    @Test
    void createStartsWithHiddenPausedDraft() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);

        assertThat(menu.getDraftVersionNumber()).isEqualTo(1);
        assertThat(menu.getPublishedVersionNumber()).isNull();
        assertThat(menu.getVisibility()).isEqualTo(MenuVisibility.HIDDEN);
        assertThat(menu.getSellingStatus()).isEqualTo(MenuSellingStatus.PAUSED);
        assertThat(menu.requireDraft().getStatus()).isEqualTo(MenuVersionStatus.DRAFT);
    }

    @Test
    void appendDraftRetiresOnlyPreviousDraft() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        MenuVersion first = menu.requireDraft();

        MenuVersion second = menu.appendDraft(content("Latte"), 11L, NOW.plusSeconds(1));

        assertThat(first.getStatus()).isEqualTo(MenuVersionStatus.RETIRED);
        assertThat(second.getVersionNumber()).isEqualTo(2);
        assertThat(menu.getDraftVersionNumber()).isEqualTo(2);
    }

    @Test
    void firstPublishShowsAndStartsSelling() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);

        MenuVersion published = menu.publish(NOW.plusSeconds(1));

        assertThat(published.getStatus()).isEqualTo(MenuVersionStatus.PUBLISHED);
        assertThat(menu.getPublishedVersionNumber()).isEqualTo(1);
        assertThat(menu.getDraftVersionNumber()).isNull();
        assertThat(menu.getVisibility()).isEqualTo(MenuVisibility.VISIBLE);
        assertThat(menu.getSellingStatus()).isEqualTo(MenuSellingStatus.SELLING);
    }

    @Test
    void replacementPublishPreservesExposureControls() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        MenuVersion first = menu.publish(NOW.plusSeconds(1));
        menu.changeVisibility(MenuVisibility.HIDDEN);
        menu.changeSellingStatus(MenuSellingStatus.PAUSED);
        menu.appendDraft(content("Latte"), 11L, NOW.plusSeconds(2));

        MenuVersion replacement = menu.publish(NOW.plusSeconds(3));

        assertThat(first.getStatus()).isEqualTo(MenuVersionStatus.RETIRED);
        assertThat(replacement.getStatus()).isEqualTo(MenuVersionStatus.PUBLISHED);
        assertThat(menu.getVisibility()).isEqualTo(MenuVisibility.HIDDEN);
        assertThat(menu.getSellingStatus()).isEqualTo(MenuSellingStatus.PAUSED);
    }

    @Test
    void scheduleKeepsPublishedVersionUntilActivationAndCanBeCancelled() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        menu.publish(NOW.plusSeconds(1));
        menu.appendDraft(content("Latte"), 11L, NOW.plusSeconds(2));

        MenuVersion scheduled = menu.schedule(NOW.plusSeconds(60), NOW.plusSeconds(3));

        assertThat(menu.getPublishedVersionNumber()).isEqualTo(1);
        assertThat(menu.getScheduledVersionNumber()).isEqualTo(2);
        assertThat(scheduled.getStatus()).isEqualTo(MenuVersionStatus.SCHEDULED);

        menu.cancelSchedule(NOW.plusSeconds(4));
        assertThat(scheduled.getStatus()).isEqualTo(MenuVersionStatus.DRAFT);
        assertThat(scheduled.getEffectiveAt()).isNull();
        assertThat(menu.getDraftVersionNumber()).isEqualTo(2);
        assertThat(menu.getScheduledVersionNumber()).isNull();
        assertThat(menu.getPublishedVersionNumber()).isEqualTo(1);
    }

    @Test
    void cancellingScheduleWithNewerDraftIsRejectedAndPreservesLatestDraft() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        MenuVersion scheduled = menu.schedule(
                NOW.plusSeconds(60), NOW.plusSeconds(1));
        MenuVersion latestDraft = menu.appendDraft(
                content("Latte"), 11L, NOW.plusSeconds(2));

        assertThatThrownBy(() -> menu.cancelSchedule(NOW.plusSeconds(3)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT);
        assertThat(menu.getVersions())
                .filteredOn(version -> version.getStatus() == MenuVersionStatus.DRAFT)
                .containsExactly(latestDraft);
        assertThat(menu.getDraftVersionNumber()).isEqualTo(2);
        assertThat(menu.getScheduledVersionNumber()).isEqualTo(1);
        assertThat(scheduled.getStatus()).isEqualTo(MenuVersionStatus.SCHEDULED);
        assertThat(ManagedMenuResponse.from(menu).draft().name()).isEqualTo("Latte");

        MenuVersion published = menu.publish(NOW.plusSeconds(4));
        assertThat(published).isSameAs(latestDraft);
        assertThat(published.getName()).isEqualTo("Latte");
        assertThat(scheduled.getStatus()).isEqualTo(MenuVersionStatus.RETIRED);
    }

    @Test
    void dueScheduledVersionReplacesPublishedVersionExactlyOnce() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        MenuVersion first = menu.publish(NOW.plusSeconds(1));
        menu.appendDraft(content("Latte"), 11L, NOW.plusSeconds(2));
        MenuVersion scheduled = menu.schedule(NOW.plusSeconds(60), NOW.plusSeconds(3));

        MenuVersion activated = menu.activateScheduled(NOW.plusSeconds(60));

        assertThat(activated).isSameAs(scheduled);
        assertThat(first.getStatus()).isEqualTo(MenuVersionStatus.RETIRED);
        assertThat(scheduled.getStatus()).isEqualTo(MenuVersionStatus.PUBLISHED);
        assertThat(menu.getPublishedVersionNumber()).isEqualTo(2);
        assertThat(menu.getScheduledVersionNumber()).isNull();
        assertThatThrownBy(() -> menu.activateScheduled(NOW.plusSeconds(61)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void controlsStayIndependent() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        menu.publish(NOW.plusSeconds(1));

        menu.changeVisibility(MenuVisibility.HIDDEN);
        assertThat(menu.getSellingStatus()).isEqualTo(MenuSellingStatus.SELLING);

        menu.changeSellingStatus(MenuSellingStatus.PAUSED);
        menu.changeVisibility(MenuVisibility.VISIBLE);
        assertThat(menu.getSellingStatus()).isEqualTo(MenuSellingStatus.PAUSED);
    }

    @Test
    void retireClearsPointersAndPreservesVersions() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        menu.publish(NOW.plusSeconds(1));
        menu.appendDraft(content("Latte"), 11L, NOW.plusSeconds(2));

        menu.retire(NOW.plusSeconds(3));

        assertThat(menu.isRetired()).isTrue();
        assertThat(menu.getDraftVersionNumber()).isNull();
        assertThat(menu.getPublishedVersionNumber()).isNull();
        assertThat(menu.getVisibility()).isEqualTo(MenuVisibility.HIDDEN);
        assertThat(menu.getSellingStatus()).isEqualTo(MenuSellingStatus.PAUSED);
        assertThat(menu.getVersions()).allMatch(v -> v.getStatus() == MenuVersionStatus.RETIRED);
    }

    @Test
    void publishingWithoutDraftIsRejected() {
        Menu menu = Menu.create(7L, content("Americano"), 11L, NOW);
        menu.publish(NOW.plusSeconds(1));

        assertThatThrownBy(() -> menu.publish(NOW.plusSeconds(2)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT);
    }

    @Test
    void publishingWithoutRequiredDisclosuresIsRejected() {
        Menu menu = Menu.create(7L, unregisteredContent("Americano"), 11L, NOW);

        assertThatThrownBy(() -> menu.publish(NOW.plusSeconds(1)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT);
    }

    @Test
    void publishingRegisteredAllergenStatusWithoutAnyDisclosureIsRejected() {
        Menu menu = Menu.create(7L, emptyRegisteredAllergenContent(), 11L, NOW);

        assertThatThrownBy(() -> menu.publish(NOW.plusSeconds(1)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT);
    }

    private MenuContent content(String name) {
        return new MenuContent(
                name,
                "description",
                5_000,
                false,
                "COFFEE",
                List.of("BEVERAGE"),
                List.of("signature"),
                true,
                true,
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new AllergenDisclosure(
                        AllergenIngredientCode.MILK,
                        AllergenDisclosureStatus.CONTAINS)),
                DisclosureRegistrationStatus.REGISTERED,
                List.of(new OriginDisclosure("원두", "콜롬비아")),
                false);
    }

    private MenuContent unregisteredContent(String name) {
        return new MenuContent(
                name,
                "description",
                5_000,
                false,
                "COFFEE",
                List.of("BEVERAGE"),
                List.of("signature"),
                true,
                true,
                DisclosureRegistrationStatus.NOT_REGISTERED,
                List.of(),
                DisclosureRegistrationStatus.NOT_REGISTERED,
                List.of(),
                false);
    }

    private MenuContent emptyRegisteredAllergenContent() {
        return new MenuContent(
                "Americano", "description", 5_000, false, "COFFEE",
                List.of(), List.of(), true, true,
                DisclosureRegistrationStatus.REGISTERED, List.of(),
                DisclosureRegistrationStatus.NOT_APPLICABLE, List.of(), false);
    }
}
