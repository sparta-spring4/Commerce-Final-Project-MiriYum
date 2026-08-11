package com.miriyum.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import com.miriyum.domain.menuhold.service.MenuHoldService;
import com.miriyum.domain.store.service.StoreService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ReservationProductionDependencyTest {

    @Test
    void fulfillmentConsumesOnlyApprovedStoreAndMenuHoldServiceSignatures()
            throws NoSuchMethodException {
        assertThat(StoreService.class.getMethod(
                "requireManagementOwnership", long.class, long.class).getReturnType())
                .isEqualTo(void.class);
        assertThat(MenuHoldService.class.getMethod(
                "lockForTermination", long.class).getReturnType())
                .isEqualTo(MenuHoldTerminationPresence.class);
        assertThat(MenuHoldService.class.getMethod(
                "fulfill", MenuHoldFulfillCommand.class).getReturnType())
                .isEqualTo(MenuHoldCommandResult.class);
    }

    @Test
    void reservationProductionDoesNotImportOtherDomainEntitiesOrRepositories()
            throws IOException {
        Path production = Path.of(
                "src", "main", "java", "com", "miriyum", "domain", "reservation");

        try (Stream<Path> files = Files.walk(production)) {
            List<String> forbiddenImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ReservationProductionDependencyTest::linesUnchecked)
                    .filter(line -> line.startsWith("import com.miriyum.domain."))
                    .filter(line -> !line.startsWith("import com.miriyum.domain.reservation."))
                    .filter(line -> line.contains(".entity.") || line.contains(".repository."))
                    .toList();

            assertThat(forbiddenImports).isEmpty();
        }
    }

    private static Stream<String> linesUnchecked(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream();
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read " + path, exception);
        }
    }
}
