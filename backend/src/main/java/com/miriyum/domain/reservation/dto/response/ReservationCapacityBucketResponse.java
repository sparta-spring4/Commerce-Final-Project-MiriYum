package com.miriyum.domain.reservation.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import java.time.LocalTime;

public record ReservationCapacityBucketResponse(
        String capacityBucketId,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        int maxPeople,
        int maxTeams,
        int occupiedPeople,
        int occupiedTeams,
        int availablePeople,
        int availableTeams
) {

    public static ReservationCapacityBucketResponse from(
            ReservationCapacityBucket bucket
    ) {
        if (bucket == null || bucket.getId() == null) {
            throw new IllegalArgumentException("saved capacity bucket is required");
        }
        return new ReservationCapacityBucketResponse(
                Long.toString(bucket.getId()),
                bucket.getStartTime(),
                bucket.getEndTime(),
                bucket.getMaxPeople(),
                bucket.getMaxTeams(),
                bucket.getOccupiedPeople(),
                bucket.getOccupiedTeams(),
                Math.max(0, bucket.getMaxPeople() - bucket.getOccupiedPeople()),
                Math.max(0, bucket.getMaxTeams() - bucket.getOccupiedTeams())
        );
    }
}
