-- 공통 MySQL 멱등 명령 기록 (#32, C-006)
-- 종류: 대리 PK(E-007) + 업무 복합 UNIQUE. 식별 컬럼은 대소문자 구분 collation.
-- Flyway 순서: PR #40(#31)의 _01·_02 이후 _03. 적용 후 이 파일은 수정하지 않는다.

CREATE TABLE idempotency_commands (
    idempotency_command_id BIGINT       NOT NULL AUTO_INCREMENT,
    principal_namespace    VARCHAR(30)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    principal_id           BIGINT       NOT NULL,
    command_type           VARCHAR(60)  COLLATE utf8mb4_0900_as_cs NOT NULL,
    idempotency_key        CHAR(36)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    request_fingerprint    CHAR(64)     COLLATE utf8mb4_0900_as_cs NOT NULL,
    processing_status      VARCHAR(20)  NOT NULL,
    result_http_status     SMALLINT     NULL,
    result_response_code   VARCHAR(40)  NULL,
    result_resource_type   VARCHAR(40)  NULL,
    result_resource_id     VARCHAR(64)  NULL,
    result_payload         TEXT         NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (idempotency_command_id),
    CONSTRAINT uk_idempotency_commands
        UNIQUE (principal_namespace, principal_id, command_type, idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
