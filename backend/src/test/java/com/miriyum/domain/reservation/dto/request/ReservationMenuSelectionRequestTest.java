package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationMenuSelectionRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("메뉴 식별자는 정밀도 손실 없는 문자열 PublicId로 받고 long으로 변환한다")
    void acceptsStringPublicIdAndConvertsItToLong() {
        // given
        ReservationMenuSelectionRequest selection = new ReservationMenuSelectionRequest(
                "9223372036854775807", 2
        );

        // when
        Set<?> violations = VALIDATOR.validate(selection);

        // then
        assertThat(violations).isEmpty();
        assertThat(selection.menuIdAsLong()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("메뉴 식별자는 0, 선행 0, 부호 또는 소수 표현을 허용하지 않는다")
    void rejectsNonPublicIdMenuValues() {
        // when
        Set<String> zeroPaths = propertyPathsOf(new ReservationMenuSelectionRequest("0", 1));
        Set<String> leadingZeroPaths = propertyPathsOf(
                new ReservationMenuSelectionRequest("01", 1)
        );
        Set<String> signedPaths = propertyPathsOf(new ReservationMenuSelectionRequest("+1", 1));
        Set<String> decimalPaths = propertyPathsOf(new ReservationMenuSelectionRequest("1.0", 1));

        // then
        assertThat(zeroPaths).contains("menuId");
        assertThat(leadingZeroPaths).contains("menuId");
        assertThat(signedPaths).contains("menuId");
        assertThat(decimalPaths).contains("menuId");
    }

    @Test
    @DisplayName("메뉴 식별자는 signed long 범위를 넘을 수 없다")
    void rejectsMenuIdOutsideSignedLongRange() {
        // given
        ReservationMenuSelectionRequest selection = new ReservationMenuSelectionRequest(
                "9223372036854775808", 1
        );

        // when
        Set<String> paths = propertyPathsOf(selection);

        // then
        assertThat(paths).contains("menuIdInRange");
    }

    @Test
    @DisplayName("메뉴 식별자 변환은 유효성 검사를 우회하지 않는다")
    void rejectsInvalidMenuIdDuringLongConversion() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationMenuSelectionRequest("0", 1).menuIdAsLong()
        );
    }

    @Test
    @DisplayName("메뉴 수량은 1개 이상 100개 이하이며 필수다")
    void validatesMenuQuantity() {
        // when
        Set<String> zeroPaths = propertyPathsOf(new ReservationMenuSelectionRequest("1", 0));
        Set<String> tooManyPaths = propertyPathsOf(
                new ReservationMenuSelectionRequest("1", 101)
        );
        Set<String> missingPaths = propertyPathsOf(
                new ReservationMenuSelectionRequest("1", null)
        );

        // then
        assertThat(zeroPaths).contains("quantity");
        assertThat(tooManyPaths).contains("quantity");
        assertThat(missingPaths).contains("quantity");
    }

    private static Set<String> propertyPathsOf(ReservationMenuSelectionRequest selection) {
        return VALIDATOR.validate(selection).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
