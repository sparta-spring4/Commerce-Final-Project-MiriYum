package com.miriyum.global.config;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Restricts centrally supplied runtime JSON to the non-secret S3 settings approved for deployment.
 */
public final class RuntimeJsonAllowlistEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String JSON_PROPERTY_SOURCE_NAME = "spring.application.json";

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
        validate(rawRuntimeJson(environment));
    }

    /**
     * Reads the original runtime JSON input without consulting Boot's parsed JSON property source.
     * A JSON document can otherwise define {@code spring.application.json} itself and mask the
     * original input during a later environment lookup.
     */
    private static String rawRuntimeJson(ConfigurableEnvironment environment) {
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (JSON_PROPERTY_SOURCE_NAME.equals(propertySource.getName())) {
                continue;
            }
            Object value = propertySource.getProperty(JSON_PROPERTY_SOURCE_NAME);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
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
        // Spring Boot's JSON processor runs at HIGHEST_PRECEDENCE + 5.
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }
}
