package com.miriyum.domain.search.service;

import com.miriyum.domain.search.interpreter.InterpretationRequest;
import com.miriyum.domain.search.interpreter.InterpretationResult;
import com.miriyum.domain.search.interpreter.RuleInterpreter;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/** 공개 검색 원문을 승인된 사전과 서울 업무 날짜 기준으로 결정적으로 해석한다. */
@Component
public class IntegratedSearchInterpreter {

    private static final int MAX_INPUT_LENGTH = 100;
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final SearchVocabularyProvider vocabularyProvider;
    private final RuleInterpreter ruleInterpreter;

    public IntegratedSearchInterpreter(
            SearchVocabularyProvider vocabularyProvider,
            Clock clock
    ) {
        this.vocabularyProvider = vocabularyProvider;
        this.ruleInterpreter = new RuleInterpreter(clock);
    }

    /** 원문을 보관하거나 기록하지 않고 현재 요청 안에서만 해석한다. */
    public InterpretationResult interpret(String searchInput) {
        if (searchInput == null
                || searchInput.isBlank()
                || searchInput.length() > MAX_INPUT_LENGTH) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        var vocabulary = vocabularyProvider.current();
        return ruleInterpreter.interpret(
                new InterpretationRequest(searchInput, vocabulary, SEOUL_ZONE));
    }
}
