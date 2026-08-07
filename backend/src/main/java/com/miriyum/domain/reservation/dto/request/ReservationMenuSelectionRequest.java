package com.miriyum.domain.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 예약 생성에서 선택하는 메뉴와 수량이다.
 *
 * @param menuId 정밀도 손실 없이 전달되는 메뉴 문자열 PublicId
 * @param quantity 선택 수량
 */
public final class ReservationMenuSelectionRequest {

    private static final Set<String> ALLOWED_FIELDS = Set.of("menuId", "quantity");

    @NotNull
    @PositiveLongPublicId
    private final String menuId;

    @NotNull @Min(1) @Max(100)
    private final Integer quantity;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ReservationMenuSelectionRequest(JsonNode input) {
        this(parseMenuId(input), parseQuantity(input));
    }

    public ReservationMenuSelectionRequest(String menuId, Integer quantity) {
        this.menuId = menuId;
        this.quantity = quantity;
    }

    @JsonProperty
    public String menuId() {
        return menuId;
    }

    @JsonProperty
    public Integer quantity() {
        return quantity;
    }

    /**
     * 저장소 식별에 사용할 signed long 메뉴 ID로 변환한다.
     *
     * @return 양의 signed long 메뉴 ID
     * @throws IllegalArgumentException 문자열 ID가 양의 signed long 10진수가 아닐 때
     */
    public long menuIdAsLong() {
        if (!PositiveLongPublicId.Validator.isValidPublicId(menuId)) {
            throw new IllegalArgumentException("menuId must be a positive signed long decimal");
        }
        return Long.parseLong(menuId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReservationMenuSelectionRequest selection)) {
            return false;
        }
        return java.util.Objects.equals(menuId, selection.menuId)
                && java.util.Objects.equals(quantity, selection.quantity);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(menuId, quantity);
    }

    private static String parseMenuId(JsonNode input) {
        return nullableString(requiredObject(input).get("menuId"), "menuId");
    }

    private static Integer parseQuantity(JsonNode input) {
        JsonNode value = requiredObject(input).get("quantity");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt()) {
            throw new IllegalArgumentException("quantity must be an integer");
        }
        return value.asInt();
    }

    private static JsonNode requiredObject(JsonNode input) {
        if (input == null || !input.isObject()
                || !ALLOWED_FIELDS.containsAll(input.propertyNames())) {
            throw new IllegalArgumentException("invalid menu selection object");
        }
        return input;
    }

    private static String nullableString(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return value.asString();
    }
}
