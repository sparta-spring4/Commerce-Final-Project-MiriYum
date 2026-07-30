package com.miriyum.domain.auth.password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호를 SHA-256 → Base64로 전처리한 뒤 BCrypt로 해싱한다.
 *
 * <p>BCrypt는 해싱할 때 입력이 72 UTF-8 byte를 넘으면 {@code IllegalArgumentException}을 던지고,
 * 검증할 때는 예외 없이 앞 72 byte만 반영한다. 반면 확정 정책
 * {@code docs/service-policies/01-member-auth.md} AUTH-006은 "최대 64자까지 허용하며 유니코드
 * 코드 포인트 하나를 한 글자로 계산"한다고 정한다. 한글은 코드 포인트 하나가 UTF-8 3 byte라
 * 25자만 넘어도 72 byte를 초과하므로, BCrypt에 원문을 그대로 넘기면 정책상 유효한 비밀번호가
 * 가입에서 실패하고 로그인에서는 73 byte 이후가 조용히 무시된다. 정책은 자동 잘라내기도
 * 금지하므로 두 동작 모두 계약 위반이다.</p>
 *
 * <p>그래서 BCrypt에 넘기기 전에 SHA-256으로 고정 길이(32 byte)로 만들고 Base64로 인코딩한다.
 * 결과는 길이·문자 집합이 항상 44 ASCII byte이므로 BCrypt 한도 안에 들어오고, 원문 길이와
 * 무관하게 모든 코드 포인트가 해시 입력에 반영된다. Base64를 한 번 더 씌우는 이유는 SHA-256
 * 원시 byte에 0x00이 나올 수 있고 BCrypt가 null byte에서 입력을 끊기 때문이다.</p>
 *
 * <p>저장 값의 형식은 {@code SecurityConfig}의 {@code DelegatingPasswordEncoder}가 붙이는
 * {@code {sha256-bcrypt}} 접두사로 구분한다. 나중에 전처리·해시 방식을 바꿔도 저장된 해시가
 * 자기 방식을 스스로 알려주므로 기존 값을 그대로 두고 새 방식만 추가할 수 있다.</p>
 */
public class Sha256BCryptPasswordEncoder implements PasswordEncoder {

    /** {@code DelegatingPasswordEncoder}가 저장 값 접두사로 쓰는 방식 식별자다. */
    public static final String ENCODING_ID = "sha256-bcrypt";

    private final PasswordEncoder bcryptPasswordEncoder;

    public Sha256BCryptPasswordEncoder(PasswordEncoder bcryptPasswordEncoder) {
        this.bcryptPasswordEncoder = bcryptPasswordEncoder;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return bcryptPasswordEncoder.encode(toFixedLengthDigest(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return bcryptPasswordEncoder.matches(toFixedLengthDigest(rawPassword), encodedPassword);
    }

    /**
     * 원문을 길이 44의 Base64 ASCII 문자열로 바꾼다. 원문이 길어도 BCrypt 한도를 넘지 않는다.
     */
    private String toFixedLengthDigest(CharSequence rawPassword) {
        byte[] digest = sha256(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
