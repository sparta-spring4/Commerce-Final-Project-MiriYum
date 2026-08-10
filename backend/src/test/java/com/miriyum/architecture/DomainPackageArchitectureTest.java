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

class DomainPackageArchitectureTest {

    private static final Path DOMAIN_SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "miriyum", "domain");
    private static final String DOMAIN_PREFIX = "com.miriyum.domain.";
    private static final Set<String> LEGACY_STORE_SUBDOMAINS = Set.of(
            "core", "schedule", "closure", "menu", "search", "recommendation");
    private static final Set<String> HTTP_BOUNDARIES = Set.of(
            "publicapi", "consumer", "storeoperator", "auth", "account");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("(?m)^import\\s+([\\w.*]+);");

    @Test
    void legacyStoreSubdomainsAreAbsent() {
        assertThat(javaSources())
                .filteredOn(source -> source.relativePath().startsWith("store/"))
                .noneMatch(source -> isBelowLegacyStoreSubdomain(source.relativePath()));
    }

    @Test
    void controllersUseAudienceOrPurposePackage() {
        assertThat(javaSources())
                .filteredOn(SourceFile::isController)
                .allMatch(SourceFile::hasExplicitHttpBoundary);
    }

    @Test
    void controllersDoNotImportEntityOrRepository() {
        assertThat(javaSources())
                .filteredOn(SourceFile::isController)
                .allMatch(source -> source.imports().stream()
                        .noneMatch(DomainPackageArchitectureTest::isEntityOrRepository));
    }

    @Test
    void domainsDoNotImportForeignEntityOrRepository() {
        assertThat(javaSources())
                .allMatch(source -> source.imports().stream()
                        .filter(DomainPackageArchitectureTest::isEntityOrRepository)
                        .noneMatch(imported -> !source.topLevelDomain()
                                .equals(topLevelDomain(imported))));
    }

    private static boolean isBelowLegacyStoreSubdomain(String relativePath) {
        int slash = relativePath.indexOf('/', "store/".length());
        String child = slash < 0
                ? relativePath.substring("store/".length())
                : relativePath.substring("store/".length(), slash);
        return LEGACY_STORE_SUBDOMAINS.contains(child);
    }

    private static boolean isEntityOrRepository(String imported) {
        return imported.startsWith(DOMAIN_PREFIX)
                && (imported.contains(".entity.") || imported.contains(".repository."));
    }

    private static String topLevelDomain(String qualifiedName) {
        String remainder = qualifiedName.substring(DOMAIN_PREFIX.length());
        int separator = remainder.indexOf('.');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }

    private static List<SourceFile> javaSources() {
        try (Stream<Path> files = Files.walk(DOMAIN_SOURCE_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(DomainPackageArchitectureTest::readSource)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static SourceFile readSource(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
            if (!packageMatcher.find()) {
                throw new IllegalStateException("Package declaration is missing: " + path);
            }
            Matcher importMatcher = IMPORT_PATTERN.matcher(content);
            List<String> imports = importMatcher.results()
                    .map(result -> result.group(1))
                    .toList();
            String relativePath = DOMAIN_SOURCE_ROOT.relativize(path)
                    .toString()
                    .replace('\\', '/');
            return new SourceFile(
                    relativePath,
                    packageMatcher.group(1),
                    imports);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record SourceFile(
            String relativePath,
            String packageName,
            List<String> imports
    ) {
        private String topLevelDomain() {
            return DomainPackageArchitectureTest.topLevelDomain(packageName);
        }

        private boolean isController() {
            return relativePath.contains("/controller/")
                    && relativePath.endsWith("Controller.java");
        }

        private boolean hasExplicitHttpBoundary() {
            String marker = "/controller/";
            int start = relativePath.indexOf(marker) + marker.length();
            int end = relativePath.indexOf('/', start);
            return end > start
                    && HTTP_BOUNDARIES.contains(relativePath.substring(start, end));
        }
    }
}
