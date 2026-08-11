package com.miriyum.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final Set<DependencyEdge> LEGACY_CYCLE_BREAK_EDGES = Set.of(
            new DependencyEdge("consumer", "reservation"),
            new DependencyEdge("reservation", "menuhold"));
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("(?m)^import\\s+([\\w.*]+);");
    private static final Pattern ENTITY_PATTERN =
            Pattern.compile("(?m)^\\s*@(?:jakarta\\.persistence\\.)?Entity\\b");

    @Test
    void legacyStoreSubdomainsAreAbsent() {
        List<SourceFile> storeSources = javaSources().stream()
                .filter(source -> source.relativePath().startsWith("store/"))
                .toList();

        assertThat(storeSources)
                .isNotEmpty()
                .noneMatch(source -> isBelowLegacyStoreSubdomain(source.relativePath()));
    }

    @Test
    void controllersUseAudienceOrPurposePackage() {
        List<SourceFile> controllers = javaSources().stream()
                .filter(SourceFile::isController)
                .toList();

        assertThat(controllers)
                .isNotEmpty()
                .allMatch(SourceFile::hasExplicitHttpBoundary);
    }

    @Test
    void controllersDoNotImportEntityOrRepository() {
        List<SourceFile> sources = javaSources();
        Set<String> persistentTypes = persistentTypeNames(sources);
        List<SourceFile> controllers = sources.stream()
                .filter(SourceFile::isController)
                .toList();

        assertThat(controllers)
                .isNotEmpty()
                .allMatch(source -> source.imports().stream()
                        .noneMatch(imported -> importsPersistentType(
                                imported, persistentTypes)));
    }

    @Test
    void domainsDoNotImportForeignEntityOrRepository() {
        List<SourceFile> sources = javaSources();
        Set<String> persistentTypes = persistentTypeNames(sources);

        assertThat(sources)
                .isNotEmpty()
                .allMatch(source -> source.imports().stream()
                        .filter(imported -> importsPersistentType(
                                imported, persistentTypes))
                        .noneMatch(imported -> !source.topLevelDomain()
                                .equals(topLevelDomain(imported))));
    }

    @Test
    void persistentTypesAreDetectedFromDeclarations() {
        assertThat(persistentTypeNames(javaSources()))
                .contains(
                        "com.miriyum.domain.auth.ratelimit.RateLimitWindow",
                        "com.miriyum.domain.auth.ratelimit.RateLimitWindowRepository",
                        "com.miriyum.domain.auth.logindelay.LoginFailureDelayRepository");
    }

    @Test
    void domainsDoNotAddDependencyCycles() {
        Map<String, Set<String>> dependencies = domainDependencies(javaSources());

        assertThat(dependencies).isNotEmpty();
        assertThat(LEGACY_CYCLE_BREAK_EDGES)
                .allMatch(edge -> dependencies.getOrDefault(edge.from(), Set.of())
                        .contains(edge.to()));
        assertThat(cyclicDomainPairs(withoutLegacyCycleBreakEdges(dependencies)))
                .isEmpty();
    }

    private static boolean isBelowLegacyStoreSubdomain(String relativePath) {
        int slash = relativePath.indexOf('/', "store/".length());
        String child = slash < 0
                ? relativePath.substring("store/".length())
                : relativePath.substring("store/".length(), slash);
        return LEGACY_STORE_SUBDOMAINS.contains(child);
    }

    private static Set<String> persistentTypeNames(List<SourceFile> sources) {
        return sources.stream()
                .filter(SourceFile::isPersistentType)
                .map(SourceFile::qualifiedName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean importsPersistentType(
            String imported,
            Set<String> persistentTypes
    ) {
        if (persistentTypes.contains(imported)) {
            return true;
        }
        if (!imported.endsWith(".*")) {
            return false;
        }
        String packagePrefix = imported.substring(0, imported.length() - 1);
        return persistentTypes.stream().anyMatch(type -> type.startsWith(packagePrefix));
    }

    private static Map<String, Set<String>> domainDependencies(List<SourceFile> sources) {
        Set<String> domains = sources.stream()
                .map(SourceFile::topLevelDomain)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        domains.forEach(domain -> dependencies.put(domain, new TreeSet<>()));
        for (SourceFile source : sources) {
            Set<String> sourceDependencies = dependencies.get(source.topLevelDomain());
            source.imports().stream()
                    .filter(imported -> imported.startsWith(DOMAIN_PREFIX))
                    .map(DomainPackageArchitectureTest::topLevelDomain)
                    .filter(importedDomain -> !importedDomain.equals(source.topLevelDomain()))
                    .forEach(sourceDependencies::add);
        }
        return dependencies;
    }

    private static Set<DomainPair> cyclicDomainPairs(
            Map<String, Set<String>> dependencies
    ) {
        List<String> domains = dependencies.keySet().stream().sorted().toList();
        Set<DomainPair> cycles = new LinkedHashSet<>();
        for (int first = 0; first < domains.size(); first++) {
            for (int second = first + 1; second < domains.size(); second++) {
                String left = domains.get(first);
                String right = domains.get(second);
                if (isReachable(left, right, dependencies)
                        && isReachable(right, left, dependencies)) {
                    cycles.add(new DomainPair(left, right));
                }
            }
        }
        return cycles;
    }

    private static Map<String, Set<String>> withoutLegacyCycleBreakEdges(
            Map<String, Set<String>> dependencies
    ) {
        Map<String, Set<String>> remaining = new LinkedHashMap<>();
        dependencies.forEach((domain, targets) ->
                remaining.put(domain, new TreeSet<>(targets)));
        for (DependencyEdge edge : LEGACY_CYCLE_BREAK_EDGES) {
            Set<String> targets = remaining.get(edge.from());
            if (targets == null || !targets.remove(edge.to())) {
                throw new IllegalStateException("legacy cycle edge is missing: " + edge);
            }
        }
        return remaining;
    }

    private static boolean isReachable(
            String start,
            String target,
            Map<String, Set<String>> dependencies
    ) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (String dependency : dependencies.getOrDefault(current, Set.of())) {
                if (dependency.equals(target)) {
                    return true;
                }
                pending.addLast(dependency);
            }
        }
        return false;
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
                    imports,
                    content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record SourceFile(
            String relativePath,
            String packageName,
            List<String> imports,
            String content
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

        private String qualifiedName() {
            return packageName + "." + simpleName();
        }

        private String simpleName() {
            int slash = relativePath.lastIndexOf('/');
            String fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
            return fileName.substring(0, fileName.length() - ".java".length());
        }

        private boolean isPersistentType() {
            return isPackageOrChild("entity")
                    || isPackageOrChild("repository")
                    || simpleName().endsWith("Repository")
                    || ENTITY_PATTERN.matcher(content).find();
        }

        private boolean isPackageOrChild(String segment) {
            return packageName.endsWith("." + segment)
                    || packageName.contains("." + segment + ".");
        }
    }

    private record DomainPair(String first, String second) {
    }

    private record DependencyEdge(String from, String to) {
    }
}
