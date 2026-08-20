package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

final class ReservationCreationCapacityValidator {

    private static final Comparator<ReservationCapacityBucket> BUCKET_ORDER =
            Comparator.comparing(ReservationCapacityBucket::getStartTime)
                    .thenComparing(ReservationCapacityBucket::getEndTime)
                    .thenComparingLong(ReservationCapacityBucket::getPolicyVersion);

    long validate(
            List<ReservationCapacityBucket> buckets,
            long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime occupancyEndTime,
            int partySize,
            boolean includesInfants
    ) {
        if (buckets == null || buckets.isEmpty()) {
            throw new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY);
        }
        long version = buckets.getFirst().getPolicyVersion();
        for (ReservationCapacityBucket bucket : buckets) {
            if (bucket.getStoreId() != storeId
                    || !bucket.getServiceDate().equals(serviceDate)
                    || bucket.getPolicyVersion() != version) {
                throw new ServiceException(ReservationErrorCode.CAPACITY_POLICY_CHANGED);
            }
            if (partySize < bucket.getMinPartySize()
                    || partySize > bucket.getMaxPartySize()
                    || (includesInfants && !bucket.isInfantsAllowed())) {
                throw new ServiceException(ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE);
            }
        }
        List<ReservationCapacityBucket> byInterval = buckets.stream()
                .sorted(BUCKET_ORDER)
                .toList();
        LocalTime coveredUntil = startTime;
        for (ReservationCapacityBucket bucket : byInterval) {
            LocalTime coveredStart = laterOf(bucket.getStartTime(), startTime);
            LocalTime coveredEnd = earlierOf(bucket.getEndTime(), occupancyEndTime);
            if (!coveredStart.equals(coveredUntil) || !coveredEnd.isAfter(coveredStart)) {
                throw new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY);
            }
            coveredUntil = coveredEnd;
        }
        if (coveredUntil.equals(occupancyEndTime)) {
            return version;
        }
        throw new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY);
    }

    private static LocalTime laterOf(LocalTime left, LocalTime right) {
        return left.isAfter(right) ? left : right;
    }

    private static LocalTime earlierOf(LocalTime left, LocalTime right) {
        return left.isBefore(right) ? left : right;
    }
}
