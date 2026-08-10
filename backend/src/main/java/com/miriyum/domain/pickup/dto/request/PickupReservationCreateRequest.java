package com.miriyum.domain.pickup.dto.request;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ValidPickupMenuSelections
public record PickupReservationCreateRequest(
        @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String storeId,
        @NotNull LocalDate pickupDate,
        @NotNull LocalTime pickupTime,
        @NotNull @Size(min = 1, max = 20)
        List<@NotNull @Valid PickupMenuSelectionRequest> menuSelections
) {
    public PickupReservationCreateRequest {
        if (pickupTime != null && (pickupTime.getSecond() != 0 || pickupTime.getNano() != 0)) {
            throw new IllegalArgumentException("pickupTime must use minute precision");
        }
        menuSelections = menuSelections == null ? null : List.copyOf(menuSelections);
    }

    public long storeIdAsLong() {
        try {
            long value = Long.parseLong(storeId);
            if (value <= 0) {
                throw new IllegalArgumentException("storeId must be positive");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("storeId must be a positive long", exception);
        }
    }

    public List<PickupMenuSelectionRequest> normalizedMenuSelections() {
        if (menuSelections == null || menuSelections.isEmpty()) {
            throw new IllegalArgumentException("menuSelections must not be empty");
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (PickupMenuSelectionRequest selection : menuSelections) {
            if (selection == null) {
                throw new IllegalArgumentException("menu selection must not be null");
            }
            long menuId = selection.menuIdAsLong();
            int quantity = Math.addExact(
                    quantities.getOrDefault(menuId, 0), selection.quantity());
            if (quantity < 1 || quantity > 100) {
                throw new IllegalArgumentException("menu quantity must be between 1 and 100");
            }
            quantities.put(menuId, quantity);
        }
        return quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PickupMenuSelectionRequest(
                        Long.toString(entry.getKey()), entry.getValue()))
                .toList();
    }
}

@Documented
@Constraint(validatedBy = ValidPickupMenuSelections.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidPickupMenuSelections {

    String message() default "동일 메뉴의 합산 수량은 100 이하여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements
            ConstraintValidator<ValidPickupMenuSelections, PickupReservationCreateRequest> {

        @Override
        public boolean isValid(
                PickupReservationCreateRequest request,
                ConstraintValidatorContext context
        ) {
            if (request == null || request.menuSelections() == null) {
                return true;
            }
            Map<String, Integer> quantities = new LinkedHashMap<>();
            for (PickupMenuSelectionRequest selection : request.menuSelections()) {
                if (selection == null || selection.menuId() == null
                        || selection.quantity() < 1 || selection.quantity() > 100) {
                    continue;
                }
                int quantity = quantities.getOrDefault(selection.menuId(), 0)
                        + selection.quantity();
                if (quantity > 100) {
                    context.disableDefaultConstraintViolation();
                    context.buildConstraintViolationWithTemplate(
                                    context.getDefaultConstraintMessageTemplate())
                            .addPropertyNode("menuSelections")
                            .addConstraintViolation();
                    return false;
                }
                quantities.put(selection.menuId(), quantity);
            }
            return true;
        }
    }
}
