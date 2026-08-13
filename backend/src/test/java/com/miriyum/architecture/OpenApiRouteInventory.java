package com.miriyum.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.Yaml;

record OpenApiRouteInventory(
        Set<ApiRoute> activeRoutes,
        Set<ApiRoute> contractOnlyRoutes,
        List<String> metadataErrors
) {

    private static final String RUNTIME_STATUS = "x-miriyum-runtime-status";
    private static final String OWNER_ISSUE = "x-miriyum-owner-issue";
    private static final String CONTRACT_ONLY = "contract-only";
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of("_template", "mvp1-common");
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final Comparator<ApiRoute> ROUTE_ORDER = Comparator
            .comparing(ApiRoute::path)
            .thenComparing(route -> route.method().name());

    OpenApiRouteInventory {
        activeRoutes = Set.copyOf(activeRoutes);
        contractOnlyRoutes = Set.copyOf(contractOnlyRoutes);
        metadataErrors = List.copyOf(metadataErrors);
    }

    static OpenApiRouteInventory load(Path specsRoot) throws IOException {
        Set<ApiRoute> active = new TreeSet<>(ROUTE_ORDER);
        Set<ApiRoute> contractOnly = new TreeSet<>(ROUTE_ORDER);
        List<String> errors = new ArrayList<>();

        for (Path file : featureOpenApiFiles(specsRoot)) {
            loadFile(file, active, contractOnly, errors);
        }
        return new OpenApiRouteInventory(active, contractOnly, errors);
    }

    Set<ApiRoute> staleContractOnlyRoutes(Set<ApiRoute> runtimeRoutes) {
        Set<ApiRoute> stale = new TreeSet<>(ROUTE_ORDER);
        stale.addAll(contractOnlyRoutes);
        stale.retainAll(runtimeRoutes);
        return Set.copyOf(stale);
    }

    private static Set<Path> featureOpenApiFiles(Path specsRoot) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        try (Stream<Path> directories = Files.list(specsRoot)) {
            directories
                    .filter(Files::isDirectory)
                    .filter(directory -> !EXCLUDED_DIRECTORIES.contains(
                            directory.getFileName().toString()))
                    .map(directory -> directory.resolve("openapi.yaml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    private static void loadFile(
            Path file,
            Set<ApiRoute> active,
            Set<ApiRoute> contractOnly,
            List<String> errors
    ) throws IOException {
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(file)) {
            document = map(new Yaml().load(input));
        }

        for (Map.Entry<String, Object> pathEntry : map(document.get("paths")).entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> pathItem = map(pathEntry.getValue());
            boolean hasStatus = pathItem.containsKey(RUNTIME_STATUS);
            boolean hasOwner = pathItem.containsKey(OWNER_ISSUE);
            boolean isContractOnly = CONTRACT_ONLY.equals(pathItem.get(RUNTIME_STATUS));

            if (hasStatus && !isContractOnly) {
                errors.add(file + " " + path + ": runtime-status must be contract-only");
            }
            if (hasStatus && !hasOwner) {
                errors.add(file + " " + path + ": contract-only requires x-miriyum-owner-issue");
            }
            if (hasOwner && !hasStatus) {
                errors.add(file + " " + path + ": owner-issue requires x-miriyum-runtime-status");
            }
            if (hasOwner && !isPositiveIssue(pathItem.get(OWNER_ISSUE))) {
                errors.add(file + " " + path + ": owner-issue must be a positive integer");
            }

            for (String operation : HTTP_METHODS) {
                if (!pathItem.containsKey(operation)) {
                    continue;
                }
                ApiRoute route = new ApiRoute(RequestMethod.valueOf(operation.toUpperCase()), path);
                if (isContractOnly) {
                    contractOnly.add(route);
                } else {
                    active.add(route);
                }
            }
        }
    }

    private static boolean isPositiveIssue(Object value) {
        return value instanceof Number number
                && number.longValue() > 0
                && number.doubleValue() == number.longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value == null) {
            return Map.of();
        }
        return (Map<String, Object>) value;
    }
}
