package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FileStorageTimeZoneConfigTest {

    @Test
    void doesNotConfigureGlobalHibernateJdbcTimeZoneForFileMetadata() throws IOException {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(applicationYaml).doesNotContain("hibernate.jdbc.time_zone");
    }
}
