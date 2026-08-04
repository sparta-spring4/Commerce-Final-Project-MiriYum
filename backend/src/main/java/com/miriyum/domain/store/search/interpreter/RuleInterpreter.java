package com.miriyum.domain.store.search.interpreter;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** 외부 AI나 조회 계층 없이 검색 원문을 허용 조건과 키워드로 결정적으로 해석한다. */
public final class RuleInterpreter {

    public static final String RULE_VERSION = "rule-v1";

    private final Clock clock;

    /**
     * 상대 날짜 판정에 사용할 시계를 주입한다.
     *
     * @param clock 테스트와 운영에서 명시적으로 선택한 시계
     */
    public RuleInterpreter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 승인된 조건만 추출하고 나머지 입력은 일반 키워드로 보존한다.
     *
     * @param request 원문·사전·시간대 스냅샷
     * @return 규칙·사전 버전을 포함한 결정적 해석 결과
     * @throws IllegalArgumentException 원문이 {@code null}인 경우
     */
    public InterpretationResult interpret(InterpretationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String normalized = SearchInputNormalizer.normalize(request.rawInput());
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                normalized);
        return new InterpretationResult(
                RULE_VERSION,
                request.vocabulary().version(),
                condition,
                List.of());
    }
}
