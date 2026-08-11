package com.miriyum.domain.pickup.dto.request;

import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.Sort;

public record PickupStoreSearchRequest(
        LocalDate pickupDate,
        PickupStatus status,
        int page,
        int size,
        Order order
) {
    public PickupStoreSearchRequest {
        if (page < 0 || size < 1 || size > 100 || order == null) {
            throw invalidRequest();
        }
    }

    public static PickupStoreSearchRequest from(
            LocalDate pickupDate,
            String status,
            Integer page,
            Integer size,
            String sort
    ) {
        return new PickupStoreSearchRequest(
                pickupDate,
                parseStatus(status),
                page == null ? 0 : page,
                size == null ? 20 : size,
                Order.fromNullable(sort));
    }

    private static PickupStatus parseStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return PickupStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    public enum Order {
        PICKUP_DATE_ASC("pickupDate,asc", "pickupDate", Sort.Direction.ASC),
        PICKUP_DATE_DESC("pickupDate,desc", "pickupDate", Sort.Direction.DESC),
        CREATED_AT_ASC("createdAt,asc", "createdAt", Sort.Direction.ASC),
        CREATED_AT_DESC("createdAt,desc", "createdAt", Sort.Direction.DESC);

        private final String externalValue;
        private final String property;
        private final Sort.Direction direction;

        Order(String externalValue, String property, Sort.Direction direction) {
            this.externalValue = externalValue;
            this.property = property;
            this.direction = direction;
        }

        public List<Sort.Order> sortOrders() {
            return List.of(
                    new Sort.Order(direction, property),
                    new Sort.Order(direction, "id"));
        }

        private static Order fromNullable(String value) {
            if (value == null) {
                return PICKUP_DATE_ASC;
            }
            return Arrays.stream(values())
                    .filter(order -> order.externalValue.equals(value))
                    .findFirst()
                    .orElseThrow(PickupStoreSearchRequest::invalidRequest);
        }
    }

    private static ServiceException invalidRequest() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
