package com.miriyum.global.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 요청 지문(fingerprint) 헬퍼다.
 *
 * <p>도메인이 구성한 정규 입력(HTTP method, 정규화 route, path parameter, 정렬·정규화한 승인 query와
 * 승인 body field 결합)을 받아 SHA-256 hex로 만든다. 비밀번호·JWT·원본 JSON 전체는 정규 입력에
 * 포함하지 않는다. 같은 route라도 대상 리소스 ID가 다르면 정규 입력이 달라야 한다.</p>
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /**
     * 정규 입력의 SHA-256 hex(소문자 64자)를 반환한다.
     *
     * @param canonicalInput 도메인이 구성한 정규 입력
     * @return 소문자 SHA-256 hex 문자열
     */
    public static String of(String canonicalInput) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
