package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReservationPartyRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

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
        ReservationCreateRequest request = new ReservationCreateRequest(
                "1", null, null, null,
                new ReservationPartyRequest(0, 0, 0), List.of()
        );

        // when
        Set<String> paths = VALIDATOR.validate(request).stream()
                .map(violation -> publicPath(violation.getPropertyPath().toString()))
                .collect(java.util.stream.Collectors.toSet());

        // then
        assertThat(paths).contains("$");
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

    @Test
    @DisplayName("인원 수는 JSON 정수 token만 허용한다")
    void rejectsFractionalPartyCountJsonToken() {
        String body = """
                {"adultCount":1.5,"childCount":0,"infantCount":0}
                """;

        assertThatThrownBy(() -> JSON_MAPPER.readValue(body, ReservationPartyRequest.class))
                .isInstanceOf(Exception.class);
    }

    private static String publicPath(String propertyPath) {
        return propertyPath.isBlank() ? "$" : propertyPath;
    }
}
