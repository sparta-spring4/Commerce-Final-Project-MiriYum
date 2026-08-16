package com.miriyum.domain.platformoperator.adminstore.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionShape;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoreSanctionPolicyCatalogTest {
    private final StoreSanctionPolicyCatalog policy = new StoreSanctionPolicyCatalog();
    private final Instant now = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void allFeatureRestrictionRequiresTwoPersonApproval() {
        var shape = new SanctionShape(SanctionType.FEATURE_RESTRICTION,
                EnumSet.allOf(RestrictedFeature.class), null, null);

        assertThat(policy.requiresApproval(shape)).isTrue();
    }

    @Test
    void futureStartIsRejectedUntilActivationWorkerExists() {
        var shape = new SanctionShape(SanctionType.FEATURE_RESTRICTION,
                Set.of(RestrictedFeature.RESERVATION), now.plusSeconds(1), null);

        assertThatThrownBy(() -> policy.validate(shape, now)).isInstanceOf(ServiceException.class);
    }
}
