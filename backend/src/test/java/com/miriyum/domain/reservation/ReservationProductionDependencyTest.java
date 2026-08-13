package com.miriyum.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.store.service.StoreService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class ReservationProductionDependencyTest {

    @Test
    void fulfillmentConsumesOnlyApprovedStoreAndReservationMenuHoldPortSignatures()
            throws NoSuchMethodException {
        assertThat(StoreService.class.getMethod(
                "requireManagementOwnership", long.class, long.class).getReturnType())
                .isEqualTo(void.class);
        assertThat(ReservationMenuHoldPort.class.getMethod(
                "lockForTermination", long.class).getReturnType())
                .isEqualTo(ReservationMenuHoldTerminationPresence.class);
        assertThat(ReservationMenuHoldPort.class.getMethod(
                "fulfill", long.class, String.class).getReturnType())
                .isEqualTo(ReservationMenuHoldResult.class);
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

    @Test
    void reservationProductionDoesNotImportMenuHoldOwnedDtos() throws IOException {
        Path production = Path.of(
                "src", "main", "java", "com", "miriyum", "domain", "reservation");

        try (Stream<Path> files = Files.walk(production)) {
            List<String> forbiddenImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ReservationProductionDependencyTest::linesUnchecked)
                    .filter(line -> line.startsWith(
                            "import com.miriyum.domain.menuhold.dto"))
                    .toList();

            assertThat(forbiddenImports).isEmpty();
        }
    }

    @Test
    void reservationHoldAuditRepositoryDoesNotExposeMutationOrDeletionApis() {
        Set<String> methods = Stream.of(
                        ReservationHoldTransitionAuditRepository.class.getMethods())
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(JpaRepository.class.isAssignableFrom(
                ReservationHoldTransitionAuditRepository.class)).isFalse();
        assertThat(methods).doesNotContain(
                "delete",
                "deleteAll",
                "deleteAllById",
                "deleteById",
                "deleteAllInBatch",
                "deleteAllByIdInBatch"
        );
    }

    private static Stream<String> linesUnchecked(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream();
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read " + path, exception);
        }
    }
}
