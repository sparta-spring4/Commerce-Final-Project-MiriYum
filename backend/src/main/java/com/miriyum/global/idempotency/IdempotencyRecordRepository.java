package com.miriyum.global.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code idempotency_commands} 접근을 JdbcTemplate로 수행한다.
 *
 * <p>유일키 경합은 JPA flush 예외 복구가 아니라 {@code INSERT IGNORE}(선점)와 {@code SELECT ... FOR SHARE}
 * (공유 잠금 조회)로 결정적으로 처리한다. 호출은 호출 도메인이 소유한 트랜잭션 안에서 실행된다.</p>
 */
@Repository
public class IdempotencyRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code PROCESSING} 행을 선점한다.
     *
     * @param command 검증된 명령 식별 정보
     * @return 이 트랜잭션이 새 행을 삽입했으면(신규 선점) {@code true}, 기존 행이 있어 무시됐으면 {@code false}.
     *         {@code INSERT IGNORE}는 UPDATE 매칭 경로가 없어 반환 행 수가 {@code CLIENT_FOUND_ROWS}와
     *         무관하게 1/0으로 일정하다.
     */
    public boolean claim(IdempotencyCommand command) {
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO idempotency_commands "
                        + "(principal_namespace, principal_id, command_type, idempotency_key, request_fingerprint, "
                        + "processing_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                command.principalNamespace(), command.principalId(), command.commandType(),
                command.idempotencyKey(), command.requestFingerprint());
        return inserted == 1;
    }

    /**
     * 업무 유일키로 최신 커밋 행을 공유 잠금으로 읽는다({@code SELECT ... FOR SHARE}).
     *
     * <p>충돌한 삽입 트랜잭션이 롤백되면 대기 중인 요청이 새 행을 삽입할 수 있으므로, {@link #claim}이
     * {@code false}를 반환한 시점에는 커밋된 중복 행이 존재한다. 경합자는 결과를 수정하지 않으므로 공유
     * 잠금이면 충분하며, 여러 경합자가 중복 키 확인에서 얻은 S 잠금을 동시에 X 잠금으로 승격할 때 발생하는
     * 데드락을 피한다. locking read를 유지하므로 {@code REPEATABLE_READ}의 이전 snapshot과 무관하게
     * 최신 커밋 결과를 읽는다.</p>
     *
     * @param command 공유 잠금으로 조회할 명령 식별 정보
     * @return 최신 커밋된 기존 명령 기록
     */
    public StoredCommand findByBusinessKeyForShare(IdempotencyCommand command) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_status, request_fingerprint, result_http_status, result_response_code, "
                        + "result_resource_type, result_resource_id, result_payload "
                        + "FROM idempotency_commands "
                        + "WHERE principal_namespace = ? AND principal_id = ? AND command_type = ? AND idempotency_key = ? "
                        + "FOR SHARE",
                (rs, rowNum) -> new StoredCommand(
                        IdempotencyStatus.valueOf(rs.getString("processing_status")),
                        rs.getString("request_fingerprint"),
                        (Integer) rs.getObject("result_http_status"),
                        rs.getString("result_response_code"),
                        rs.getString("result_resource_type"),
                        rs.getString("result_resource_id"),
                        rs.getString("result_payload")),
                command.principalNamespace(), command.principalId(), command.commandType(), command.idempotencyKey());
    }

    /**
     * 선점한 행을 최초 성공 결과와 함께 {@code SUCCEEDED}로 확정한다.
     *
     * @param command 확정할 명령 식별 정보
     * @param httpStatus 최초 성공 HTTP 상태
     * @param responseCode 최초 성공 응답 코드
     * @param resourceType 결과 리소스 유형
     * @param resourceId 결과 리소스 ID
     * @param payloadJson 최초 성공 응답 데이터의 JSON 문자열
     */
    public void markSucceeded(IdempotencyCommand command, int httpStatus, String responseCode,
            String resourceType, String resourceId, String payloadJson) {
        jdbcTemplate.update(
                "UPDATE idempotency_commands SET processing_status = 'SUCCEEDED', result_http_status = ?, "
                        + "result_response_code = ?, result_resource_type = ?, result_resource_id = ?, "
                        + "result_payload = ?, updated_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE principal_namespace = ? AND principal_id = ? AND command_type = ? AND idempotency_key = ?",
                httpStatus, responseCode, resourceType, resourceId, payloadJson,
                command.principalNamespace(), command.principalId(), command.commandType(), command.idempotencyKey());
    }

    /**
     * 잠금 조회 결과의 저장된 명령 상태다.
     */
    public record StoredCommand(
            IdempotencyStatus status,
            String requestFingerprint,
            Integer resultHttpStatus,
            String resultResponseCode,
            String resultResourceType,
            String resultResourceId,
            String resultPayload
    ) {
    }
}
