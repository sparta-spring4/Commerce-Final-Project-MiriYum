package com.miriyum.domain.recommendation.ranking;

public record RecommendationReason(String code, String message) {

    public static final RecommendationReason KEYWORD =
            new RecommendationReason("KEYWORD_MATCH", "검색어와 잘 맞아요");
    public static final RecommendationReason STORE_CATEGORY =
            new RecommendationReason("STORE_CATEGORY_MATCH", "찾는 매장 종류와 잘 맞아요");
    public static final RecommendationReason MENU_PRIMARY_CATEGORY =
            new RecommendationReason("MENU_PRIMARY_CATEGORY_MATCH", "찾는 메뉴 종류가 있어요");
    public static final RecommendationReason MENU_SECONDARY_CATEGORY =
            new RecommendationReason("MENU_SECONDARY_CATEGORY_MATCH", "원하는 메뉴 특징과 잘 맞아요");
    public static final RecommendationReason TAG =
            new RecommendationReason("TAG_MATCH", "선택한 취향 태그와 잘 맞아요");
    public static final RecommendationReason AVAILABILITY =
            new RecommendationReason("AVAILABILITY_MATCH", "예약 가능 여부를 반영했어요");
    public static final RecommendationReason VISITED_STORE =
            new RecommendationReason("VISITED_STORE", "이전에 이용한 매장이에요");
    public static final RecommendationReason ORDERED_MENU =
            new RecommendationReason("ORDERED_MENU", "이전에 선택한 메뉴가 있어요");

    public RecommendationReason {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("recommendation reason values are required");
        }
    }
}
