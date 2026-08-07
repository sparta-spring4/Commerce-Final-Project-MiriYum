package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationPartyRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("성인·아동·영유아는 모두 전체 인원에 포함한다")
    void includesEveryPartyMemberInTotalCount() {
        // given
        ReservationPartyRequest party = new ReservationPartyRequest(2, 1, 1);

        // when
        Set<?> violations = VALIDATOR.validate(party);

        // then
        assertThat(violations).isEmpty();
        assertThat(party.totalCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("각 인원은 0명 이상 100명 이하만 허용한다")
    void validatesEachPartyCountRange() {
        // given
        ReservationPartyRequest party = new ReservationPartyRequest(-1, 101, 0);

        // when
        Set<String> paths = VALIDATOR.validate(party).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        // then
        assertThat(paths).containsExactlyInAnyOrder("adultCount", "childCount");
    }

    @Test
    @DisplayName("전체 인원은 영유아만인 경우를 포함해 1명 이상이어야 한다")
    void requiresAtLeastOnePartyMember() {
        // given
        ReservationPartyRequest party = new ReservationPartyRequest(0, 0, 0);

        // when
        Set<String> paths = VALIDATOR.validate(party).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        // then
        assertThat(paths).contains("totalCountValid");
    }

    @Test
    @DisplayName("성인·아동·영유아 인원은 모두 필수다")
    void requiresEveryPartyCount() {
        // given
        ReservationPartyRequest party = new ReservationPartyRequest(null, null, null);

        // when
        Set<String> paths = VALIDATOR.validate(party).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        // then
        assertThat(paths).contains("adultCount", "childCount", "infantCount");
    }
}
