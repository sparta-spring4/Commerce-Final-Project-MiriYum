package com.miriyum.domain.auth.contact;

/**
 * API 응답에 표시할 휴대전화 번호를 마스킹한다.
 */
public final class PhoneNumberMasker {

    private PhoneNumberMasker() {
    }

    public static String mask(String normalizedPhoneNumber) {
        if (normalizedPhoneNumber == null) {
            return null;
        }
        if (normalizedPhoneNumber.length() != 11) {
            return "****";
        }
        return normalizedPhoneNumber.substring(0, 3)
                + "-****-"
                + normalizedPhoneNumber.substring(7);
    }
}
