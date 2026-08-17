package com.miriyum.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochService;
import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationNoShowAuditRepository;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.waiting.service.WaitingStoreAuthority;
import com.miriyum.domain.reservation.waiting.service.WaitingStoreAuthorityPort;
import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.service.StoreService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class ReservationProductionDependencyTest {

    private static final Set<String> APPROVED_NOTIFICATION_CONTRACT_IMPORTS = Set.of(
            "import com.miriyum.domain.notification.dto.source.NotificationActionAvailability;",
            "import com.miriyum.domain.notification.dto.source.NotificationActionType;",
            "import com.miriyum.domain.notification.dto.source.NotificationPurpose;",
            "import com.miriyum.domain.notification.dto.source.NotificationResourceType;",
            "import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;"
    );

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
        assertThat(ReservationMenuHoldPort.class.getMethod(
                "forfeit", long.class, String.class).getReturnType())
                .isEqualTo(ReservationMenuHoldResult.class);
    }

    @Test
    void qrVisitConsumesOnlyTheApprovedAuthEpochContract() throws NoSuchMethodException {
        assertThat(ConsumerQrEpochService.class.getMethod(
                "captureCurrent", Long.class).getReturnType())
                .isEqualTo(ConsumerQrEpochSnapshot.class);
        assertThat(ConsumerQrEpochService.class.getMethod(
                "requireCurrent", Long.class, ConsumerQrEpochSnapshot.class).getReturnType())
                .isEqualTo(void.class);
    }

    @Test
    void waitingConsumesOnlyTheApprovedManagedStorePublicContract()
            throws NoSuchMethodException {
        assertThat(StoreService.class.getMethod(
                "getManagedStore", long.class, long.class).getReturnType())
                .isEqualTo(ManagedStoreResponse.class);
        assertThat(StoreService.class.getMethod(
                "findDisplayName", long.class).getReturnType())
                .isEqualTo(Optional.class);
        assertThat(WaitingStoreAuthorityPort.class.getMethod(
                "requireRead", long.class, long.class).getReturnType())
                .isEqualTo(WaitingStoreAuthority.class);
        assertThat(WaitingStoreAuthorityPort.class.getMethod(
                "requireMutation", long.class, long.class).getReturnType())
                .isEqualTo(WaitingStoreAuthority.class);
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
                    .filter(ReservationProductionDependencyTest::isForbiddenForeignInternalImport)
                    .toList();

            assertThat(forbiddenImports).isEmpty();
        }
    }

    @Test
    void notificationPublicEnumsAreAllowedButNotificationRepositoriesRemainForbidden() {
        assertThat(isForbiddenForeignInternalImport(
                "import com.miriyum.domain.notification.dto.source.NotificationPurpose;"
        )).isFalse();
        assertThat(isForbiddenForeignInternalImport(
                "import com.miriyum.domain.notification.repository.NotificationTaskRepository;"
        )).isTrue();
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

    private static boolean isForbiddenForeignInternalImport(String line) {
        return line.startsWith("import com.miriyum.domain.")
                && !line.startsWith("import com.miriyum.domain.reservation.")
                && (line.contains(".entity.") || line.contains(".repository."))
                && !APPROVED_NOTIFICATION_CONTRACT_IMPORTS.contains(line);
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

    @Test
    void visitAuditRepositoriesExposeNoDeleteOrUpdateApi() {
        assertAppendOnly(ReservationCheckInAuditRepository.class);
        assertAppendOnly(ReservationNoShowAuditRepository.class);
    }

    private static void assertAppendOnly(Class<?> repositoryType) {
        Set<String> methods = Stream.of(repositoryType.getMethods())
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(JpaRepository.class.isAssignableFrom(repositoryType)).isFalse();
        assertThat(methods).doesNotContain(
                "delete", "deleteAll", "deleteAllById", "deleteById",
                "deleteAllInBatch", "deleteAllByIdInBatch"
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
