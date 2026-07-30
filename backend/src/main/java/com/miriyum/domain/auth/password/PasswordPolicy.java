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
        String normalized = Normalizer.normalize(rawPassword, Normalizer.Form.NFC);
        validate(normalized);
        return normalized;
    }

    private void validate(String password) {
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (countCharacterClasses(password) < MIN_CHARACTER_CLASSES) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
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
