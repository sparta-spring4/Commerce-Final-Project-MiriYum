package com.miriyum.global.idempotency;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyRecordRepository.StoredCommand;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 상태 변경 명령을 멱등하게 실행한다.
 *
 * <p>호출 도메인의 주 Service가 트랜잭션을 소유하고, 이 wrapper는 {@code Propagation.MANDATORY}로
 * 참여만 한다. 격리 수준·제한 시간은 트랜잭션을 시작하는 도메인 Service가 선언한다.</p>
 *
 * <p>선점은 {@link IdempotencyRecordRepository#claim}의 신규 삽입 여부로 소유권을 판정한다. 이 트랜잭션이
 * 새로 선점한 경우에만 업무 콜백을 정확히 1회 실행한다. 같은 지문 재요청은 저장된 결과를 재생하고, 다른
 * 지문의 키 재사용은 {@code COMMON_007}로 거절한다. 커밋된 {@code PROCESSING} 발견은 불변식 위반이다.</p>
 */
@Component
public class IdempotencyExecutor {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 호출 트랜잭션에 참여해 명령을 최초 실행하거나 저장 결과를 재생한다.
     *
     * @param command 검증·정규화된 명령 식별 정보
     * @param businessWork 신규 선점한 경우에만 실행할 업무 콜백
     * @param <T> 업무 결과 데이터 타입
     * @return 신규 실행 또는 재생된 멱등 결과
     * @throws ServiceException 같은 업무 키를 다른 요청 지문으로 재사용한 경우
     * @throws IllegalStateException 커밋된 멱등 기록이 상태·결과 불변식을 위반한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentOutcome execute(IdempotencyCommand command, Supplier<BusinessResult<T>> businessWork) {
        if (repository.claim(command)) {
            BusinessResult<T> result = businessWork.get();
            String payloadJson = serialize(result.data());
            repository.markSucceeded(command, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(), payloadJson);
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(), payloadJson);
        }

        StoredCommand stored = repository.lockByBusinessKey(command);
        return switch (stored.status()) {
            case SUCCEEDED -> {
                if (!stored.requestFingerprint().equals(command.requestFingerprint())) {
                    throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                requireSucceededResult(stored);
                yield new IdempotentOutcome(true, stored.resultHttpStatus(),
                        stored.resultResponseCode(), stored.resultResourceType(),
                        stored.resultResourceId(), stored.resultPayload());
            }
            case PROCESSING -> throw new IllegalStateException(
                    "커밋된 PROCESSING 멱등 기록이 발견되었습니다: 불변식 위반");
        };
    }

    private String serialize(Object data) {
        if (data == null) {
            return null;
        }
        return objectMapper.writeValueAsString(data);
    }

    private static void requireSucceededResult(StoredCommand stored) {
        if (stored.resultHttpStatus() == null || stored.resultResponseCode() == null) {
            throw new IllegalStateException(
                    "결과가 누락된 SUCCEEDED 멱등 기록이 발견되었습니다: 불변식 위반");
        }
    }
}
