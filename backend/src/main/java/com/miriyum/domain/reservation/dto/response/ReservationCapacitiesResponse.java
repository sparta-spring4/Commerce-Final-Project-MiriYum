package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import java.time.LocalDate;
import java.util.List;

public record ReservationCapacitiesResponse(
        LocalDate serviceDate,
        long policyVersion,
        List<ReservationCapacityBucketResponse> buckets
) {

    public ReservationCapacitiesResponse {
        if (serviceDate == null || policyVersion <= 0 || buckets == null) {
            throw new IllegalArgumentException("valid reservation capacities are required");
        }
        buckets = List.copyOf(buckets);
    }

    public static ReservationCapacitiesResponse from(
            LocalDate serviceDate,
            long policyVersion,
            List<ReservationCapacityBucket> buckets
    ) {
        if (buckets == null) {
            throw new IllegalArgumentException("capacity buckets are required");
        }
        return new ReservationCapacitiesResponse(
                serviceDate,
                policyVersion,
                buckets.stream()
                        .map(ReservationCapacityBucketResponse::from)
                        .toList()
        );
    }
}
