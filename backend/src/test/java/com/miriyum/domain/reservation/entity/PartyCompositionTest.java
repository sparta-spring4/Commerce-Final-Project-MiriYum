package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PartyCompositionTest {

    @Test
    @DisplayName("성인·아동·영아 수와 전체 인원 수를 보존한다")
    void preservesPartyCompositionAndCalculatesTotal() {
        // when
        PartyComposition party = PartyComposition.of(2, 1, 1);

        // then
        assertThat(party.getAdultCount()).isEqualTo(2);
        assertThat(party.getChildCount()).isEqualTo(1);
        assertThat(party.getInfantCount()).isEqualTo(1);
        assertThat(party.totalCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("전체 인원이 0명이면 생성할 수 없다")
    void rejectsEmptyParty() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PartyComposition.of(0, 0, 0));
    }

    @Test
    @DisplayName("각 인원 수는 음수일 수 없다")
    void rejectsNegativeCount() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PartyComposition.of(-1, 1, 1));
    }

    @Test
    @DisplayName("각 인원 수는 공개 요청 최대값 100을 초과할 수 없다")
    void rejectsCountAbovePublicMaximum() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PartyComposition.of(101, 0, 0));
    }
}
