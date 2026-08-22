package com.miriyum.domain.store.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StoreBusinessTypeMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V70__remove_store_business_type.sql");

    @Test
    void removesBusinessTypeFromStoreAndOnboardingSnapshot() throws Exception {
        assertThat(MIGRATION).exists();

        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();
        assertThat(sql)
                .contains("ALTER TABLE stores")
                .contains("DROP CHECK ck_stores_business_type")
                .contains("DROP COLUMN business_type")
                .contains("ALTER TABLE store_onboarding_application_versions");
    }
}
