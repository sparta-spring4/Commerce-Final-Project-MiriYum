package com.miriyum.domain.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 예약 인원 구성이다. 영유아를 포함한 전체 인원은 수용량 계산에 사용된다.
 *
 * @param adultCount 성인 인원
 * @param childCount 아동 인원
 * @param infantCount 영유아 인원
 */
public record ReservationPartyRequest(
        @NotNull @Min(0) @Max(100) Integer adultCount,
        @NotNull @Min(0) @Max(100) Integer childCount,
        @NotNull @Min(0) @Max(100) Integer infantCount
) {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "adultCount", "childCount", "infantCount"
    );

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ReservationPartyRequest(JsonNode input) {
        this(parse(input));
    }

    private ReservationPartyRequest(PartyCounts counts) {
        this(counts.adultCount(), counts.childCount(), counts.infantCount());
    }

    /**
     * 수용량에 반영할 전체 인원을 반환한다.
     *
     * @return 성인·아동·영유아 인원의 합
     * @throws IllegalStateException 인원 검증 전 누락된 값이 있을 때
     */
    public int totalCount() {
        if (adultCount == null || childCount == null || infantCount == null) {
            throw new IllegalStateException("all party counts are required");
        }
        return adultCount + childCount + infantCount;
    }

    private static PartyCounts parse(JsonNode input) {
        if (input == null || !input.isObject()
                || !ALLOWED_FIELDS.containsAll(input.propertyNames())) {
            throw new IllegalArgumentException("invalid reservation party object");
        }
        return new PartyCounts(
                nullableInteger(input.get("adultCount"), "adultCount"),
                nullableInteger(input.get("childCount"), "childCount"),
                nullableInteger(input.get("infantCount"), "infantCount")
        );
    }

    private static Integer nullableInteger(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt()) {
            throw new IllegalArgumentException(fieldName + " must be an integer");
        }
        return value.asInt();
    }

    private record PartyCounts(Integer adultCount, Integer childCount, Integer infantCount) {
    }
}

@Documented
@Constraint(validatedBy = ValidReservationParty.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidReservationParty {

    String message() default "전체 인원은 1명 이상이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ValidReservationParty, ReservationCreateRequest> {

        @Override
        public boolean isValid(ReservationCreateRequest request, ConstraintValidatorContext context) {
            if (request == null || request.party() == null) {
                return true;
            }
            ReservationPartyRequest party = request.party();
            if (party.adultCount() == null || party.childCount() == null || party.infantCount() == null) {
                return true;
            }
            return party.totalCount() >= 1;
        }
    }
}
