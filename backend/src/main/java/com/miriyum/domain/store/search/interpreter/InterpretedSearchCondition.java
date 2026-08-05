package com.miriyum.domain.store.search.interpreter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * 검색 조회 계층에 전달할 허용 조건과 남은 일반 키워드다.
 *
 * @param regionCodes 지역 승인 코드
 * @param storeCategoryCodes 매장 카테고리 승인 코드
 * @param menuCategoryCodes 메뉴 카테고리 승인 코드
 * @param tagCodes 태그 승인 코드
 * @param priceRange 원화 가격 범위
 * @param partySize 방문 인원
 * @param reservationDate 예약 날짜
 * @param reservationTime 예약 시각
 * @param remainingKeyword 조건으로 소비하지 않은 정규화 키워드
 */
public record InterpretedSearchCondition(
        List<String> regionCodes,
        List<String> storeCategoryCodes,
        List<String> menuCategoryCodes,
        List<String> tagCodes,
        PriceRange priceRange,
        Integer partySize,
        LocalDate reservationDate,
        LocalTime reservationTime,
        String remainingKeyword) {

    public InterpretedSearchCondition {
        regionCodes = List.copyOf(regionCodes);
        storeCategoryCodes = List.copyOf(storeCategoryCodes);
        menuCategoryCodes = List.copyOf(menuCategoryCodes);
        tagCodes = List.copyOf(tagCodes);
        Objects.requireNonNull(remainingKeyword, "remainingKeyword must not be null");
    }
}
