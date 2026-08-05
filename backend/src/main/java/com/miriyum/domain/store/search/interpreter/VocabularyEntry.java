package com.miriyum.domain.store.search.interpreter;

import java.util.List;

/**
 * 검색 사전에서 서버가 승인한 불투명 코드와 사용자 입력 별칭을 묶는다.
 *
 * @param code 조회 계층이 해석할 승인 코드
 * @param aliases 이 코드로 해석할 하나 이상의 별칭
 */
public record VocabularyEntry(String code, List<String> aliases) {

    public VocabularyEntry {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (aliases == null || aliases.isEmpty()) {
            throw new IllegalArgumentException("aliases must not be empty");
        }
        if (aliases.stream().anyMatch(alias -> alias == null || alias.isBlank())) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        aliases = List.copyOf(aliases);
    }
}
