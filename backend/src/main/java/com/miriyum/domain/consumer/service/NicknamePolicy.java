package com.miriyum.domain.consumer.service;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 닉네임 정규화와 형식·예약어 검증을 담당한다.
 * {@code docs/service-policies/01-member-auth.md} AUTH-008을 따른다.
 */
@Component
public class NicknamePolicy {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 20;

    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("^[가-힣A-Za-z0-9 _-]+$");
    private static final Pattern PHONE_LIKE = Pattern.compile("^[0-9]{7,11}$");

    private static final Set<String> RESERVED_WORDS = Set.of(
            "miriyum", "미리윰", "admin", "관리자", "운영자",
            "official", "공식", "고객센터", "customerservice", "support", "system", "시스템"
    );

    /**
     * NFC 정규화, 앞뒤 공백 제거, 연속 공백 정규화 뒤 형식·예약어를 검증한다.
     * 검증에 실패하면 {@link CommonErrorCode#VALIDATION_FAILED}를 던진다.
     */
    public String normalize(String rawNickname) {
        String normalized = Normalizer.normalize(rawNickname, Normalizer.Form.NFC)
                .trim()
                .replaceAll("\\s+", " ");
        validate(normalized);
        return normalized;
    }

    private void validate(String nickname) {
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (!ALLOWED_CHARACTERS.matcher(nickname).matches()) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (looksLikePhoneNumber(nickname) || looksLikeEmailOrUrl(nickname) || containsReservedWord(nickname)) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean looksLikePhoneNumber(String nickname) {
        String digitsOnly = nickname.replaceAll("[-\\s]", "");
        return PHONE_LIKE.matcher(digitsOnly).matches();
    }

    private boolean looksLikeEmailOrUrl(String nickname) {
        String lower = nickname.toLowerCase();
        return lower.contains("@") || lower.contains("http://") || lower.contains("https://") || lower.contains("www.");
    }

    private boolean containsReservedWord(String nickname) {
        String lower = nickname.toLowerCase();
        return RESERVED_WORDS.stream().anyMatch(word -> lower.contains(word.toLowerCase()));
    }
}
