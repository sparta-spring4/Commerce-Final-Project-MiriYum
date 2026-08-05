package com.miriyum.domain.menuhold;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuHoldProductionDependencyTest {

    private static final Path MENU_HOLD_SOURCE =
            Path.of("src/main/java/com/miriyum/domain/menuhold");
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.miriyum.domain.reservation.entity",
            "com.miriyum.domain.reservation.repository",
            "com.miriyum.domain.store.core.entity",
            "com.miriyum.domain.store.core.repository",
            "com.miriyum.domain.store.menu.entity",
            "com.miriyum.domain.store.menu.repository",
            "com.miriyum.domain.store.schedule.entity",
            "com.miriyum.domain.store.schedule.repository");

    @Test
    void productionDoesNotDependOnReservationOrStoreEntitiesAndRepositories()
            throws IOException {
        try (var sources = Files.walk(MENU_HOLD_SOURCE)) {
            List<Path> violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::containsForbiddenImport)
                    .toList();

            assertThat(violations).isEmpty();
        }
    }

    private boolean containsForbiddenImport(Path source) {
        try {
            String content = Files.readString(source);
            return FORBIDDEN_IMPORTS.stream().anyMatch(content::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect " + source, exception);
        }
    }
}
