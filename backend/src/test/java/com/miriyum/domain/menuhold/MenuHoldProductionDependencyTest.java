package com.miriyum.domain.menuhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuHoldProductionDependencyTest {

    private static final Path MENU_HOLD_SOURCE =
            Path.of("src/main/java/com/miriyum/domain/menuhold");
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.miriyum.domain.reservation.entity",
            "com.miriyum.domain.reservation.repository",
            "com.miriyum.domain.store.entity",
            "com.miriyum.domain.store.repository",
            "com.miriyum.domain.menu.entity",
            "com.miriyum.domain.menu.repository",
            "com.miriyum.domain.schedule.entity",
            "com.miriyum.domain.schedule.repository",
            "com.miriyum.domain.pickup.entity",
            "com.miriyum.domain.pickup.repository");
    private static final List<String> FORBIDDEN_PUBLIC_TYPE_PACKAGES = List.of(
            "com.miriyum.domain.menuhold.entity",
            "com.miriyum.domain.menuhold.repository",
            "com.miriyum.domain.menuhold.inventory.dto",
            "com.miriyum.domain.menuhold.inventory.entity",
            "com.miriyum.domain.menuhold.inventory.repository");

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

    @Test
    void publicInventoryContractDoesNotExposeInternalPersistenceTypes() {
        List<String> violations = new ArrayList<>();

        for (var method : MenuInventoryTransactionService.class.getMethods()) {
            inspectType(method.getGenericReturnType(), method.getName(), violations);
            for (Type parameterType : method.getGenericParameterTypes()) {
                inspectType(parameterType, method.getName(), violations);
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void menuHoldOwnedContractsDoNotImportReservationOwnedDtos() throws IOException {
        List<Path> ownedContractPaths = List.of(
                MENU_HOLD_SOURCE.resolve("dto"),
                MENU_HOLD_SOURCE.resolve("service"));

        List<Path> violations = new ArrayList<>();
        for (Path contractPath : ownedContractPaths) {
            try (var sources = Files.walk(contractPath)) {
                violations.addAll(sources
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> containsText(
                                path, "com.miriyum.domain.reservation.port.dto"))
                        .toList());
            }
        }

        assertThat(violations).isEmpty();
    }

    private boolean containsForbiddenImport(Path source) {
        try {
            String content = Files.readString(source);
            return FORBIDDEN_IMPORTS.stream().anyMatch(content::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect " + source, exception);
        }
    }

    private boolean containsText(Path source, String forbiddenText) {
        try {
            return Files.readString(source).contains(forbiddenText);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect " + source, exception);
        }
    }

    private void inspectType(Type type, String methodName, List<String> violations) {
        if (type instanceof Class<?> typeClass) {
            String typeName = typeClass.getName();
            if (FORBIDDEN_PUBLIC_TYPE_PACKAGES.stream().anyMatch(typeName::startsWith)) {
                violations.add(methodName + ": " + typeName);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            inspectType(parameterizedType.getRawType(), methodName, violations);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                inspectType(argument, methodName, violations);
            }
        }
    }
}
