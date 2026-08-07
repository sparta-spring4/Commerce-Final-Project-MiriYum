package com.miriyum.domain.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 일반 사용자의 즉시 확정 예약 생성 요청이다.
 */
@ValidReservationParty
@ValidMenuSelections
public final class ReservationCreateRequest {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "storeId", "serviceDate", "startTime", "startOffset", "party", "menuSelections"
    );

    @NotBlank
    @PositiveLongPublicId
    private final String storeId;

    @NotNull
    private final LocalDate serviceDate;

    @NotNull
    private final LocalTime startTime;

    @Pattern(regexp = "^[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)$")
    private final String startOffset;

    @NotNull
    @Valid
    private final ReservationPartyRequest party;

    @Valid
    @Size(max = 20)
    private final List<@NotNull @Valid ReservationMenuSelectionRequest> menuSelections;

    private final boolean menuSelectionsExplicitlyNull;

    /**
     * JSON 요청의 허용 필드와 token 유형을 경계에서 검증한다.
     *
     * @param input 역직렬화할 JSON 객체
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ReservationCreateRequest(JsonNode input) {
        this(parseStoreId(input), parseServiceDate(input), parseStartTime(input),
                parseStartOffset(input), parseParty(input), parseMenuSelections(input),
                hasExplicitNullMenuSelections(input));
    }

    public ReservationCreateRequest(
            String storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            String startOffset,
            ReservationPartyRequest party,
            List<ReservationMenuSelectionRequest> menuSelections
    ) {
        this(storeId, serviceDate, startTime, startOffset, party, menuSelections, false);
    }

    private ReservationCreateRequest(
            String storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            String startOffset,
            ReservationPartyRequest party,
            List<ReservationMenuSelectionRequest> menuSelections,
            boolean menuSelectionsExplicitlyNull
    ) {
        if (startTime != null && (startTime.getSecond() != 0 || startTime.getNano() != 0)) {
            throw new IllegalArgumentException("startTime must use minute precision");
        }
        this.storeId = storeId;
        this.serviceDate = serviceDate;
        this.startTime = startTime;
        this.startOffset = startOffset;
        this.party = party;
        this.menuSelections = menuSelections == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(menuSelections));
        this.menuSelectionsExplicitlyNull = menuSelectionsExplicitlyNull;
    }

    @JsonProperty
    public String storeId() {
        return storeId;
    }

    @JsonProperty
    public LocalDate serviceDate() {
        return serviceDate;
    }

    @JsonProperty
    public LocalTime startTime() {
        return startTime;
    }

    @JsonProperty
    public String startOffset() {
        return startOffset;
    }

    @JsonProperty
    public ReservationPartyRequest party() {
        return party;
    }

    @JsonProperty
    public List<ReservationMenuSelectionRequest> menuSelections() {
        return menuSelections;
    }

    /**
     * 저장소 식별에 사용할 signed long 매장 ID로 변환한다.
     *
     * @return 양의 signed long 매장 ID
     * @throws IllegalArgumentException 문자열 ID가 양의 signed long 10진수가 아닐 때
     */
    public long storeIdAsLong() {
        if (!PositiveLongPublicId.Validator.isValidPublicId(storeId)) {
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

    boolean hasExplicitNullMenuSelections() {
        return menuSelectionsExplicitlyNull;
    }

    private static String parseStoreId(JsonNode input) {
        return nullableString(requiredObject(input).get("storeId"), "storeId");
    }

    private static LocalDate parseServiceDate(JsonNode input) {
        String value = nullableString(requiredObject(input).get("serviceDate"), "serviceDate");
        return value == null ? null : LocalDate.parse(value);
    }

    private static LocalTime parseStartTime(JsonNode input) {
        String value = nullableString(requiredObject(input).get("startTime"), "startTime");
        return value == null ? null : LocalTime.parse(value);
    }

    private static String parseStartOffset(JsonNode input) {
        return nullableString(requiredObject(input).get("startOffset"), "startOffset");
    }

    private static ReservationPartyRequest parseParty(JsonNode input) {
        JsonNode value = requiredObject(input).get("party");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("party must be an object");
        }
        return new ReservationPartyRequest(value);
    }

    private static List<ReservationMenuSelectionRequest> parseMenuSelections(JsonNode input) {
        JsonNode value = requiredObject(input).get("menuSelections");
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("menuSelections must be an array");
        }

        List<ReservationMenuSelectionRequest> selections = new ArrayList<>();
        for (JsonNode selection : value) {
            if (selection == null || selection.isNull()) {
                selections.add(null);
            } else if (selection.isObject()) {
                selections.add(new ReservationMenuSelectionRequest(selection));
            } else {
                throw new IllegalArgumentException("menu selection must be an object");
            }
        }
        return selections;
    }

    private static boolean hasExplicitNullMenuSelections(JsonNode input) {
        JsonNode value = requiredObject(input).get("menuSelections");
        return value != null && value.isNull();
    }

    private static JsonNode requiredObject(JsonNode input) {
        if (input == null || !input.isObject()
                || !ALLOWED_FIELDS.containsAll(input.propertyNames())) {
            throw new IllegalArgumentException("invalid reservation request object");
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

@Documented
@Constraint(validatedBy = PositiveLongPublicId.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface PositiveLongPublicId {

    String message() default "유효하지 않은 공개 식별자입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<PositiveLongPublicId, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || isValidPublicId(value);
        }

        static boolean isValidPublicId(String value) {
            if (value == null || !value.matches("^[1-9][0-9]*$")) {
                return false;
            }
            try {
                return Long.parseLong(value) > 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
    }
}

@Documented
@Constraint(validatedBy = ValidMenuSelections.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidMenuSelections {

    String message() default "메뉴 선택은 null일 수 없습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ValidMenuSelections, ReservationCreateRequest> {

        @Override
        public boolean isValid(ReservationCreateRequest request, ConstraintValidatorContext context) {
            if (request == null || !request.hasExplicitNullMenuSelections()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("menuSelections")
                    .addConstraintViolation();
            return false;
        }
    }
}
