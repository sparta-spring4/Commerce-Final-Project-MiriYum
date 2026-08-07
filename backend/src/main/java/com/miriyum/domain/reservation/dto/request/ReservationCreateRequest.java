package com.miriyum.domain.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일반 사용자의 즉시 확정 예약 생성 요청이다.
 *
 * @param storeId 정밀도 손실 없이 전달되는 매장 문자열 PublicId
 * @param serviceDate 매장 현지 업무 날짜
 * @param startTime 매장 현지 시작 시각
 * @param startOffset DST 중복 시각을 식별하는 선택적 UTC offset
 * @param party 성인·아동·영유아 인원 구성
 * @param menuSelections 선택 메뉴 목록. 없거나 비어 있으면 메뉴 홀드를 만들지 않는다
 */
public record ReservationCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[1-9][0-9]*$")
        String storeId,
        @NotNull LocalDate serviceDate,
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @Pattern(regexp = "^[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)$") String startOffset,
        @NotNull @Valid ReservationPartyRequest party,
        @Valid
        @Size(max = 20)
        List<@NotNull @Valid ReservationMenuSelectionRequest> menuSelections
) {

    private static final String PUBLIC_ID_PATTERN = "^[1-9][0-9]*$";

    public ReservationCreateRequest {
        if (startTime != null && (startTime.getSecond() != 0 || startTime.getNano() != 0)) {
            throw new IllegalArgumentException("startTime must use minute precision");
        }
        menuSelections = menuSelections == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(menuSelections));
    }

    /**
     * 저장소 식별에 사용할 signed long 매장 ID로 변환한다.
     *
     * @return 양의 signed long 매장 ID
     * @throws IllegalArgumentException 문자열 ID가 양의 signed long 10진수가 아닐 때
     */
    public long storeIdAsLong() {
        if (storeId == null || !storeId.matches(PUBLIC_ID_PATTERN) || !isStoreIdInRange()) {
            throw new IllegalArgumentException("storeId must be a positive signed long decimal");
        }
        return Long.parseLong(storeId);
    }

    /**
     * 선택된 UTC offset을 {@link ZoneOffset}으로 변환한다.
     *
     * @return 선택되지 않았으면 {@code null}, 그렇지 않으면 요청 offset
     */
    public ZoneOffset startOffsetAsZoneOffset() {
        return startOffset == null ? null : ZoneOffset.of(startOffset);
    }

    /**
     * 반복된 메뉴를 최초 입력 순서로 합산한 목록을 반환한다.
     *
     * @return 메뉴 ID별 수량이 하나로 합산된 선택 목록
     */
    public List<ReservationMenuSelectionRequest> normalizedMenuSelections() {
        Map<String, Integer> quantitiesByMenuId = new LinkedHashMap<>();
        for (ReservationMenuSelectionRequest selection : menuSelections) {
            quantitiesByMenuId.merge(selection.menuId(), selection.quantity(), Math::addExact);
        }
        return quantitiesByMenuId.entrySet().stream()
                .map(entry -> new ReservationMenuSelectionRequest(entry.getKey(), entry.getValue()))
                .toList();
    }

    @AssertTrue
    public boolean isStoreIdInRange() {
        if (storeId == null || !storeId.matches(PUBLIC_ID_PATTERN)) {
            return true;
        }
        try {
            return Long.parseLong(storeId) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
