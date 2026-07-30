package com.miriyum.global.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyCommandTest {

    private static final String VALID_KEY = "123e4567-e89b-12d3-a456-426614174000";
    private static final String VALID_FINGERPRINT = "a".repeat(64);

    @Test
    @DisplayName("DB 식별자 길이와 필수값 계약을 벗어난 명령을 거부한다")
    void constructor_invalidIdentity_rejected() {
        // given
        CommandInput[] invalidInputs = {
                new CommandInput("", 1L, "STORE_REGISTER"),
                new CommandInput("A".repeat(31), 1L, "STORE_REGISTER"),
                new CommandInput("store-operator", 0L, "STORE_REGISTER"),
                new CommandInput("store-operator", 1L, ""),
                new CommandInput("store-operator", 1L, "A".repeat(61))
        };

        // when & then
        for (CommandInput input : invalidInputs) {
            assertThatThrownBy(() -> new IdempotencyCommand(
                    input.principalNamespace(),
                    input.principalId(),
                    input.commandType(),
                    VALID_KEY,
                    VALID_FINGERPRINT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("표준 namespace와 대문자 상수 형식이 아닌 명령 유형을 거부한다")
    void constructor_nonCanonicalIdentity_rejected() {
        // given
        String[] invalidNamespaces = {"CONSUMER", "STORE_OPERATOR", "store_operator", "unknown"};
        String[] invalidCommandTypes = {"store_register", "STORE-REGISTER", " STORE_REGISTER"};

        // when & then
        for (String namespace : invalidNamespaces) {
            assertThatThrownBy(() -> new IdempotencyCommand(
                    namespace, 1L, "STORE_REGISTER", VALID_KEY, VALID_FINGERPRINT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("principalNamespace");
        }
        for (String commandType : invalidCommandTypes) {
            assertThatThrownBy(() -> new IdempotencyCommand(
                    "store-operator", 1L, commandType, VALID_KEY, VALID_FINGERPRINT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandType");
        }
    }

    @Test
    @DisplayName("소문자 표준 UUID가 아닌 멱등 키를 거부한다")
    void constructor_nonNormalizedKey_rejected() {
        // when & then
        assertThatThrownBy(() -> new IdempotencyCommand(
                "store-operator",
                1L,
                "STORE_REGISTER",
                "123E4567-E89B-12D3-A456-426614174000",
                VALID_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("소문자 SHA-256 hex가 아닌 요청 지문을 거부한다")
    void constructor_invalidFingerprint_rejected() {
        // when & then
        assertThatThrownBy(() -> new IdempotencyCommand(
                "store-operator",
                1L,
                "STORE_REGISTER",
                VALID_KEY,
                "G".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record CommandInput(
            String principalNamespace,
            long principalId,
            String commandType
    ) {
    }
}
