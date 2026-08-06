package com.miriyum.domain.auth.password;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.text.Normalizer;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 정규화와 형식 검증을 담당한다. {@code docs/service-policies/01-member-auth.md}
 * AUTH-006을 따르며 일반 사용자·매장 운영자가 같은 기준을 공유한다.
 */
@Component
public class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;
    private static final int MIN_CHARACTER_CLASSES = 3;

    /**
     * NFC 정규화 뒤 길이·문자 종류 조합을 검증한다.
     * 검증에 실패하면 {@link CommonErrorCode#VALIDATION_FAILED}를 던진다.
     */
    public String normalize(String rawPassword) {
        String normalized = toNfc(rawPassword);
        validate(normalized);
        return normalized;
    }

    /**
     * 검증 없이 NFC로만 정규화한다. 로그인처럼 이미 저장된 해시와 비교만 하는 경로에서 쓴다.
     * 가입 시 NFC로 정규화해 해시했으므로, 로그인도 같은 정규화를 거쳐야 NFD로 입력해도 일치한다.
     * 로그인 실패는 항상 자격 증명 오류로만 응답해야 하므로 여기서 형식 검증을 다시 하지 않는다.
     */
    public String toNfc(String rawPassword) {
        return Normalizer.normalize(rawPassword, Normalizer.Form.NFC);
    }

    /**
     * 길이는 UTF-16 code unit이 아니라 유니코드 code point로 센다. AUTH-006이 "코드 포인트 하나를
     * 한 글자로 계산"하도록 정하므로, 이모지처럼 surrogate pair로 저장되는 문자도 한 글자로 센다.
     * 인코더의 byte 한도는 {@link Sha256BCryptPasswordEncoder}가 전처리로 흡수하므로 여기서
     * byte 길이를 따로 제한하지 않는다.
     */
    private void validate(String password) {
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        rejectEmoji(password);
        if (countCharacterClasses(password) < MIN_CHARACTER_CLASSES) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void rejectEmoji(String password) {
        for (int offset = 0; offset < password.length();) {
            int codePoint = password.codePointAt(offset);
            if (isEmojiCodePoint(codePoint)) {
                throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
            }
            offset += Character.charCount(codePoint);
        }
    }

    private boolean isEmojiCodePoint(int codePoint) {
        return codePoint == 0x200D
                || codePoint == 0xFE0F
                || codePoint == 0xFE0E
                || codePoint == 0x20E3
                || codePoint == 0x00A9
                || codePoint == 0x00AE
                || codePoint == 0x203C
                || codePoint == 0x2049
                || codePoint == 0x2122
                || codePoint == 0x2139
                || codePoint == 0x3030
                || codePoint == 0x303D
                || codePoint == 0x3297
                || codePoint == 0x3299
                || isBetween(codePoint, 0x2600, 0x27BF)
                || isBetween(codePoint, 0x2B00, 0x2BFF)
                || isBetween(codePoint, 0x1F000, 0x1FAFF);
    }

    private boolean isBetween(int codePoint, int start, int end) {
        return codePoint >= start && codePoint <= end;
    }

    /**
     * 영문 대문자·소문자·숫자·특수문자 네 종류 중 몇 종류가 포함됐는지 센다.
     * ASCII 영문·숫자가 아닌 문자(공백, 기호, 유니코드 문자 포함)는 전부 특수문자로 취급한다.
     */
    private int countCharacterClasses(String password) {
        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (int i = 0; i < password.length(); i++) {
            char character = password.charAt(i);
            if (character >= 'A' && character <= 'Z') {
                hasUppercase = true;
            } else if (character >= 'a' && character <= 'z') {
                hasLowercase = true;
            } else if (character >= '0' && character <= '9') {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }

        int classes = 0;
        if (hasUppercase) {
            classes++;
        }
        if (hasLowercase) {
            classes++;
        }
        if (hasDigit) {
            classes++;
        }
        if (hasSpecial) {
            classes++;
        }
        return classes;
    }
}
