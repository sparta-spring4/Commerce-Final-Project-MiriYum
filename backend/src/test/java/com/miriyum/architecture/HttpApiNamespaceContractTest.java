package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HttpApiNamespaceContractTest {

    private static final Path DOMAIN_SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "miriyum", "domain");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern ROOT_MAPPING_PATTERN =
            Pattern.compile("@RequestMapping\\s*\\(\\s*\"([^\"]+)\"");
    private static final Set<String> LEGACY_ROOTS = Set.of(
            "/api/v1/consumer-auth",
            "/api/v1/consumer-accounts",
            "/api/v1/reservations",
            "/api/v1/pickup-reservations",
            "/api/v1/store-operator-auth",
            "/api/v1/store-operator-accounts",
            "/api/v1/store-operator");

    @Test
    void audienceControllersUseOnlyCanonicalRoots() {
        List<ControllerMapping> mappings = controllerMappings();

        assertThat(mappings).isNotEmpty();
        assertThat(mappings).noneMatch(mapping -> LEGACY_ROOTS.stream()
                .anyMatch(legacy -> mapping.path().equals(legacy)
                        || mapping.path().startsWith(legacy + "/")));
        assertThat(mappings.stream().filter(ControllerMapping::isConsumer))
                .allMatch(mapping -> mapping.path().startsWith("/api/v1/consumers"));
        assertThat(mappings.stream().filter(ControllerMapping::isStoreOperator))
                .allMatch(mapping -> mapping.path().startsWith("/api/v1/store-operators"));
        assertThat(mappings.stream().filter(ControllerMapping::isPlatformOperator))
                .allMatch(mapping -> mapping.path().startsWith("/api/v1/platform-operators"));
    }

    private static List<ControllerMapping> controllerMappings() {
        try (Stream<Path> paths = Files.walk(DOMAIN_SOURCE_ROOT)) {
            return paths.filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .map(HttpApiNamespaceContractTest::mapping)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ControllerMapping mapping(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
            Matcher mappingMatcher = ROOT_MAPPING_PATTERN.matcher(source);
            if (!packageMatcher.find() || !mappingMatcher.find()) {
                throw new AssertionError("Controller package or root mapping missing: " + path);
            }
            return new ControllerMapping(packageMatcher.group(1), mappingMatcher.group(1));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record ControllerMapping(String packageName, String path) {

        boolean isConsumer() {
            return packageName.startsWith("com.miriyum.domain.consumer.controller.")
                    || packageName.contains(".controller.consumer");
        }

        boolean isStoreOperator() {
            return packageName.startsWith("com.miriyum.domain.storeoperator.controller.")
                    || packageName.contains(".controller.storeoperator");
        }

        boolean isPlatformOperator() {
            return packageName.startsWith("com.miriyum.domain.platformoperator.controller.");
        }
    }
}
