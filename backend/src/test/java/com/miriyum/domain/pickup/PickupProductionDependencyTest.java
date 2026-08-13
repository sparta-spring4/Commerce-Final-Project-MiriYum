package com.miriyum.domain.pickup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PickupProductionDependencyTest {

    private static final Set<String> APPROVED_NOTIFICATION_CONTRACT_IMPORTS = Set.of(
            "import com.miriyum.domain.notification.entity.NotificationActionAvailability;",
            "import com.miriyum.domain.notification.entity.NotificationActionType;",
            "import com.miriyum.domain.notification.entity.NotificationPurpose;",
            "import com.miriyum.domain.notification.entity.NotificationResourceType;",
            "import com.miriyum.domain.notification.entity.NotificationSourceDomain;"
    );

    @Test
    void pickupConsumesOnlyPublicContractsFromOtherDomains() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "miriyum", "domain", "pickup");
        List<Path> sources;
        try (var paths = Files.walk(root)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }

        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            String code = Files.readString(source);
            List<String> forbiddenImports = code.lines()
                    .filter(PickupProductionDependencyTest::isForbiddenForeignInternalImport)
                    .toList();
            assertThat(forbiddenImports)
                    .as("foreign Entity/Repository import in %s", source)
                    .isEmpty();
            assertThat(code)
                    .as("pickup must not reuse the Reservation aggregate in %s", source)
                    .doesNotContain("com.miriyum.domain.reservation");
        }
    }

    @Test
    void notificationPublicEnumsAreAllowedButNotificationRepositoriesRemainForbidden() {
        assertThat(isForbiddenForeignInternalImport(
                "import com.miriyum.domain.notification.entity.NotificationPurpose;"
        )).isFalse();
        assertThat(isForbiddenForeignInternalImport(
                "import com.miriyum.domain.notification.repository.NotificationTaskRepository;"
        )).isTrue();
    }

    private static boolean isForbiddenForeignInternalImport(String line) {
        return line.startsWith("import com.miriyum.domain.")
                && !line.startsWith("import com.miriyum.domain.pickup.")
                && (line.contains(".entity.") || line.contains(".repository."))
                && !APPROVED_NOTIFICATION_CONTRACT_IMPORTS.contains(line);
    }
}
