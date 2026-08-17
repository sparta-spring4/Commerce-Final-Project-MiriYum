package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FileStorageMigrationContractTest {

    @Test
    void fileMetadataMigrationUsesFlywayV32() {
        Path migrationDirectory = Path.of("src/main/resources/db/migration");

        assertThat(Files.exists(migrationDirectory.resolve("V32__create_file_metadata.sql"))).isTrue();
        assertThat(Files.exists(migrationDirectory.resolve("V42__enforce_file_metadata_purpose_visibility.sql"))).isTrue();
        assertThat(Files.exists(migrationDirectory.resolve("V51__add_file_metadata_cleanup_reconciliation.sql"))).isTrue();
        assertThat(Files.exists(migrationDirectory.resolve("V31__create_file_metadata.sql"))).isFalse();
    }
}
