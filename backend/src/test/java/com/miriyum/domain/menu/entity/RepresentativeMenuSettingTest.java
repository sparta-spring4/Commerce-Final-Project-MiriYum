package com.miriyum.domain.menu.entity;

import static com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus.CONFIGURED;
import static com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus.REQUIRES_ATTENTION;
import static com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus.UNCONFIGURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentativeMenuSettingTest {

    @Test
    void replacesTheWholeOrderedSelectionAndAdvancesVersion() {
        RepresentativeMenuSetting setting = RepresentativeMenuSetting.create(7L);

        assertThat(setting.getVersion()).isZero();
        assertThat(setting.getStatus()).isEqualTo(UNCONFIGURED);
        assertThat(setting.orderedMenuIds()).isEmpty();

        setting.replace(List.of(11L, 12L, 13L));

        assertThat(setting.getVersion()).isEqualTo(1L);
        assertThat(setting.getStatus()).isEqualTo(CONFIGURED);
        assertThat(setting.orderedMenuIds()).containsExactly(11L, 12L, 13L);
    }

    @Test
    void removingASelectedMenuAdvancesVersionAndRequiresAttentionBelowThree() {
        RepresentativeMenuSetting setting = RepresentativeMenuSetting.create(7L);
        setting.replace(List.of(11L, 12L, 13L));

        assertThat(setting.remove(12L)).isTrue();

        assertThat(setting.getVersion()).isEqualTo(2L);
        assertThat(setting.getStatus()).isEqualTo(REQUIRES_ATTENTION);
        assertThat(setting.orderedMenuIds()).containsExactly(11L, 13L);
    }

    @Test
    void removingAnUnselectedMenuIsANoOp() {
        RepresentativeMenuSetting setting = RepresentativeMenuSetting.create(7L);
        setting.replace(List.of(11L, 12L, 13L));

        assertThat(setting.remove(99L)).isFalse();

        assertThat(setting.getVersion()).isEqualTo(1L);
        assertThat(setting.getStatus()).isEqualTo(CONFIGURED);
        assertThat(setting.orderedMenuIds()).containsExactly(11L, 12L, 13L);
    }

    @Test
    void replacementRequiresThreeToFiveDistinctPositiveMenuIds() {
        RepresentativeMenuSetting setting = RepresentativeMenuSetting.create(7L);

        assertThatThrownBy(() -> setting.replace(List.of(11L, 12L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setting.replace(List.of(1L, 2L, 3L, 4L, 5L, 6L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setting.replace(List.of(11L, 11L, 13L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setting.replace(List.of(0L, 12L, 13L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
