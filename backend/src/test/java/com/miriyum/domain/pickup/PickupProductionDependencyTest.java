package com.miriyum.domain.pickup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PickupProductionDependencyTest {

    private static final Pattern FOREIGN_INTERNAL_IMPORT = Pattern.compile(
            "import com\\.miriyum\\.domain\\.(?!pickup\\.).*\\.(?:entity|repository)\\.");

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
            assertThat(FOREIGN_INTERNAL_IMPORT.matcher(code).find())
                    .as("foreign Entity/Repository import in %s", source)
                    .isFalse();
            assertThat(code)
                    .as("pickup must not reuse the Reservation aggregate in %s", source)
                    .doesNotContain("com.miriyum.domain.reservation");
        }
    }
}
