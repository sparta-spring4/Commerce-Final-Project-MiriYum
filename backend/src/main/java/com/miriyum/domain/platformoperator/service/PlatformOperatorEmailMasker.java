package com.miriyum.domain.platformoperator.service;

/** 플랫폼 운영자 계정 이메일을 원문 복원이 어렵도록 응답 전용 형태로 마스킹한다. */
public final class PlatformOperatorEmailMasker {
    private PlatformOperatorEmailMasker() {
    }

    public static String mask(String email) {
        if (email == null || email.indexOf('@') <= 0 || email.indexOf('@') != email.lastIndexOf('@')) {
            return "***";
        }
        String[] address = email.split("@", -1);
        String[] domain = address[1].split("\\.", -1);
        if (address[0].isEmpty() || domain.length < 2 || domain[0].isEmpty()
                || java.util.Arrays.stream(domain).anyMatch(String::isEmpty)) {
            return "***";
        }
        int visible = address[0].length() <= 2 ? 1 : 2;
        String local = address[0].substring(0, visible) + "*".repeat(address[0].length() - visible);
        String firstDomain = domain[0].substring(0, 1) + "*".repeat(domain[0].length() - 1);
        return local + "@" + firstDomain + "." + String.join(".", java.util.Arrays.copyOfRange(domain, 1, domain.length));
    }
}
