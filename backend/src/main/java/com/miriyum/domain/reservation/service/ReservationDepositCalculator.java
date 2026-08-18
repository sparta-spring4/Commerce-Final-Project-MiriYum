package com.miriyum.domain.reservation.service;

import com.miriyum.domain.menu.dto.contract.RepresentativeMenuItem;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy.Status;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reservation-owned immutable input selection and PAY-002 deposit calculation. */
@Component
public class ReservationDepositCalculator {

    static final long ALGORITHM_VERSION = 1L;
    private static final long ROUNDING_UNIT_MINOR = 1_000L;
    private static final String CURRENCY = "KRW";

    public Calculation calculate(
            StoreReservationDepositPolicy policy,
            RepresentativeMenuSnapshot representativeMenus,
            int partySize
    ) {
        requireEnabledPolicy(policy);
        requirePositive(partySize, "partySize");
        if (representativeMenus == null
                || !String.valueOf(policy.storeId()).equals(representativeMenus.storeId())
                || representativeMenus.status() != RepresentativeMenuSettingStatus.CONFIGURED
                || representativeMenus.version() <= 0
                || representativeMenus.items().isEmpty()
                || representativeMenus.items().stream().anyMatch(
                        item -> !isValidRepresentativeItem(item))) {
            throw new ServiceException(StoreErrorCode.MENU_STATE_CONFLICT);
        }

        List<ItemSnapshot> itemSnapshots = representativeMenus.items().stream()
                .map(item -> new ItemSnapshot(
                        item.menuId(), item.publishedVersionNumber(), item.price()))
                .toList();
        long priceTotal = itemSnapshots.stream()
                .mapToLong(ItemSnapshot::price)
                .sum();
        long amountMinor = calculateRoundedAmount(
                priceTotal,
                partySize,
                policy.ratePercent().getAsInt(),
                itemSnapshots.size()
        );

        return new Calculation(
                policy.policyVersion().getAsLong(),
                policy.ratePercent().getAsInt(),
                ALGORITHM_VERSION,
                partySize,
                amountMinor,
                CURRENCY,
                representativeMenus.version(),
                priceTotal,
                itemSnapshots.size(),
                itemSnapshots
        );
    }

    private static long calculateRoundedAmount(
            long priceTotal,
            int partySize,
            int ratePercent,
            int menuCount
    ) {
        BigInteger numerator = BigInteger.valueOf(priceTotal)
                .multiply(BigInteger.valueOf(partySize))
                .multiply(BigInteger.valueOf(ratePercent));
        BigInteger amountPerRoundingUnitDenominator = BigInteger.valueOf(menuCount)
                .multiply(BigInteger.valueOf(100L))
                .multiply(BigInteger.valueOf(ROUNDING_UNIT_MINOR));
        BigInteger roundedUnits = numerator
                .add(amountPerRoundingUnitDenominator.subtract(BigInteger.ONE))
                .divide(amountPerRoundingUnitDenominator);
        return roundedUnits
                .multiply(BigInteger.valueOf(ROUNDING_UNIT_MINOR))
                .longValueExact();
    }

    private static void requireEnabledPolicy(StoreReservationDepositPolicy policy) {
        if (policy == null || policy.status() != Status.ENABLED) {
            throw new IllegalArgumentException("enabled deposit policy is required");
        }
    }

    private static boolean isValidRepresentativeItem(RepresentativeMenuItem item) {
        return item != null
                && item.menuId() != null
                && !item.menuId().isBlank()
                && item.publishedVersionNumber() > 0
                && item.price() > 0
                && (item.sellingStatus() == MenuSellingStatus.SELLING
                || item.sellingStatus() == MenuSellingStatus.SOLD_OUT);
    }

    private static void requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    public record Calculation(
            long storePolicyVersion,
            int ratePercent,
            long algorithmVersion,
            int partySize,
            long amountMinor,
            String currency,
            long representativeMenuVersion,
            long representativeMenuPriceTotal,
            int representativeMenuCount,
            List<ItemSnapshot> items
    ) {
        public Calculation {
            items = List.copyOf(items);
        }
    }

    public record ItemSnapshot(
            String menuId,
            int publishedVersionNumber,
            int price
    ) {
    }
}
