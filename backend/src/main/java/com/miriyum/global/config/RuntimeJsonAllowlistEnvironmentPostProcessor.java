package com.miriyum.global.config;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Restricts centrally supplied runtime JSON to the non-secret S3 settings approved for deployment.
 */
public final class RuntimeJsonAllowlistEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> ALLOWED_PROPERTIES = Set.of(
            "miriyum.storage.s3.enabled",
            "miriyum.storage.s3.bucket",
            "miriyum.storage.s3.region",
            "miriyum.storage.s3.max-size-bytes",
            "miriyum.storage.s3.reconciliation.enabled",
            "miriyum.storage.s3.reconciliation.delay-ms",
            "miriyum.storage.s3.reconciliation.batch-size",
            "miriyum.storage.s3.reconciliation.pending-min-age-seconds",
            "miriyum.storage.s3.reconciliation.long-stay-threshold-seconds",
            "miriyum.storage.s3.reconciliation.retry-base-delay-seconds",
            "miriyum.storage.s3.reconciliation.claim-lease-seconds"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment.getProperty("spring.application.json"));
    }

    static void validate(String runtimeJson) {
        if (runtimeJson == null || runtimeJson.isBlank() || "{}".equals(runtimeJson.trim())) {
            return;
        }

        Map<String, Object> root = parse(runtimeJson);
        validateNode(root, "");
    }

    private static Map<String, Object> parse(String runtimeJson) {
        try {
            return JsonParserFactory.getJsonParser().parseMap(runtimeJson);
        } catch (RuntimeException exception) {
            throw invalidRuntimeConfig();
        }
    }

    private static void validateNode(Object value, String parentPath) {
        if (!(value instanceof Map<?, ?> node)) {
            if (!ALLOWED_PROPERTIES.contains(parentPath)) {
                throw invalidRuntimeConfig();
            }
            return;
        }

        if (!parentPath.isEmpty() && ALLOWED_PROPERTIES.stream()
                .noneMatch(allowedProperty -> allowedProperty.startsWith(parentPath + "."))) {
            throw invalidRuntimeConfig();
        }

        Iterator<? extends Map.Entry<?, ?>> fields = node.entrySet().iterator();
        while (fields.hasNext()) {
            Map.Entry<?, ?> field = fields.next();
            if (!(field.getKey() instanceof String propertyName)) {
                throw invalidRuntimeConfig();
            }
            String propertyPath = parentPath.isEmpty()
                    ? propertyName
                    : parentPath + "." + propertyName;
            validateNode(field.getValue(), propertyPath);
        }
    }

    private static IllegalStateException invalidRuntimeConfig() {
        return new IllegalStateException("SPRING_APPLICATION_JSON contains an unsupported runtime property");
    }

    @Override
    public int getOrder() {
        // Run after Spring has exposed SPRING_APPLICATION_JSON as an environment property.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
