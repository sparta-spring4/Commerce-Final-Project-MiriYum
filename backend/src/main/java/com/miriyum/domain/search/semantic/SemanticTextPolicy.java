package com.miriyum.domain.search.semantic;

import java.util.regex.Pattern;

/** 외부 임베딩 제공자에 보낼 수 없는 연락처·민감 표현을 차단한다. */
final class SemanticTextPolicy {

    private static final Pattern SENSITIVE_EXPRESSION = Pattern.compile(
            "(?i)([\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|01[016789][- ]?\\d{3,4}[- ]?\\d{4}"
                    + "|알레르기|알러지|연락처|전화번호"
                    + "|\\d{2,3}\\.\\d{3,}\s*[,/]\s*\\d{2,3}\\.\\d{3,}"
                    + "|(?:로|길|동|번지)\\s*\\d+)");

    private SemanticTextPolicy() {
    }

    static boolean allowsExternalEmbedding(String text) {
        return text != null
                && !text.isBlank()
                && !SENSITIVE_EXPRESSION.matcher(text).find();
    }
}
