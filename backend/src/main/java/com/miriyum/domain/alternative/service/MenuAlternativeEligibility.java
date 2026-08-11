package com.miriyum.domain.alternative.service;

import com.miriyum.domain.alternative.model.AlternativeMenuCandidate;
import com.miriyum.domain.alternative.model.AlternativeMenuSource;
import com.miriyum.domain.alternative.model.AlternativeReasonCode;
import com.miriyum.domain.alternative.model.EligibleAlternative;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MenuAlternativeEligibility {
    public Optional<EligibleAlternative> evaluate(AlternativeMenuSource source,
            AlternativeMenuCandidate candidate, Set<String> excludedAllergens) {
        if (source.menuId() == candidate.menuId()
                || !source.primaryCategoryCode().equals(candidate.primaryCategoryCode())) {
            return Optional.empty();
        }
        long candidatePrice = (long) candidate.unitPrice() * 100L;
        long sourcePrice = source.unitPrice();
        if (candidatePrice < sourcePrice * 80L || candidatePrice > sourcePrice * 120L) {
            return Optional.empty();
        }
        Set<String> excluded = Set.copyOf(excludedAllergens);
        if (!excluded.isEmpty()) {
            if (!"REGISTERED".equals(candidate.allergenInformationStatus())) {
                return Optional.empty();
            }
            boolean unsafe = candidate.allergens().stream().anyMatch(allergen ->
                    excluded.contains(allergen.ingredientCode())
                            && ("CONTAINS".equals(allergen.disclosureStatus())
                            || "MAY_CONTAIN".equals(allergen.disclosureStatus())));
            if (unsafe) {
                return Optional.empty();
            }
        }
        Set<String> secondary = new HashSet<>(source.secondaryCategoryCodes());
        secondary.retainAll(new HashSet<>(candidate.secondaryCategoryCodes()));
        var reasons = new ArrayList<AlternativeReasonCode>();
        reasons.add(AlternativeReasonCode.SAME_PRIMARY_CATEGORY);
        reasons.add(AlternativeReasonCode.PRICE_WITHIN_20_PERCENT);
        if (!secondary.isEmpty()) reasons.add(AlternativeReasonCode.SECONDARY_CATEGORY_MATCH);
        if (!excluded.isEmpty()) reasons.add(AlternativeReasonCode.ALLERGEN_FILTER_PASSED);
        return Optional.of(new EligibleAlternative(candidate, secondary.size(),
                Math.abs(candidate.unitPrice() - source.unitPrice()), reasons));
    }
}
