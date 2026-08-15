package com.miriyum.testinfra;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** V43 감사 불변성 trigger를 생성할 수 있도록 정적 MySQL test container를 시작 전에 구성한다. */
public final class MySqlAuditTriggerExtension implements BeforeAllCallback {

    private static final String TRUST_TRIGGER_CREATORS = "--log-bin-trust-function-creators=1";

    @Override
    public void beforeAll(ExtensionContext context) throws IllegalAccessException {
        Class<?> type = context.getRequiredTestClass();
        while (type != null && type != Object.class) {
            configureStaticContainers(type);
            type = type.getSuperclass();
        }
    }

    private static void configureStaticContainers(Class<?> type) throws IllegalAccessException {
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !JdbcDatabaseContainer.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (!field.trySetAccessible()) {
                throw new IllegalStateException("cannot access MySQL test container: " + field);
            }
            JdbcDatabaseContainer<?> container = (JdbcDatabaseContainer<?>) field.get(null);
            if (container != null && container.getDockerImageName().startsWith("mysql:")
                    && !container.isRunning()) {
                container.withCommand(TRUST_TRIGGER_CREATORS);
            }
        }
    }
}
