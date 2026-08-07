package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 예약 생성에서 선택하는 메뉴와 수량이다.
 *
 * @param menuId 정밀도 손실 없이 전달되는 메뉴 문자열 PublicId
 * @param quantity 선택 수량
 */
public record ReservationMenuSelectionRequest(
        @NotBlank
        @Pattern(regexp = "^[1-9][0-9]*$")
        String menuId,
        @NotNull @Min(1) @Max(100) Integer quantity
) {

    private static final String PUBLIC_ID_PATTERN = "^[1-9][0-9]*$";

    /**
     * 저장소 식별에 사용할 signed long 메뉴 ID로 변환한다.
     *
     * @return 양의 signed long 메뉴 ID
     * @throws IllegalArgumentException 문자열 ID가 양의 signed long 10진수가 아닐 때
     */
    public long menuIdAsLong() {
        if (menuId == null || !menuId.matches(PUBLIC_ID_PATTERN) || !isMenuIdInRange()) {
            throw new IllegalArgumentException("menuId must be a positive signed long decimal");
        }
        return Long.parseLong(menuId);
    }

    @AssertTrue
    public boolean isMenuIdInRange() {
        if (menuId == null || !menuId.matches(PUBLIC_ID_PATTERN)) {
            return true;
        }
        try {
            return Long.parseLong(menuId) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
