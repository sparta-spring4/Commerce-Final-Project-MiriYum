package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCheckInQrTokenServiceTest {

    private final ReservationCheckInQrTokenService tokenService =
            new ReservationCheckInQrTokenService();

    @Test
    @DisplayName("256-bit base64url opaque QR와 SHA-256 digest를 생성한다")
    void generatesOpaqueTokenAndDigest() throws NoSuchAlgorithmException {
        ReservationCheckInQrTokenService.GeneratedToken generated = tokenService.generate();

        assertThat(generated.rawToken()).matches("^rqg_v1_[A-Za-z0-9_-]{43}$");
        byte[] entropy = Base64.getUrlDecoder().decode(generated.rawToken().substring(7));
        assertThat(entropy).hasSize(32);
        assertThat(generated.digest()).containsExactly(
                MessageDigest.getInstance("SHA-256")
                        .digest(generated.rawToken().getBytes(StandardCharsets.US_ASCII))
        );
    }

    @Test
    @DisplayName("연속 생성한 QR은 raw와 digest가 모두 다르다")
    void generatesDistinctTokens() {
        ReservationCheckInQrTokenService.GeneratedToken first = tokenService.generate();
        ReservationCheckInQrTokenService.GeneratedToken second = tokenService.generate();

        assertThat(first.rawToken()).isNotEqualTo(second.rawToken());
        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    @Test
    @DisplayName("정확한 QR 형식만 digest할 수 있다")
    void rejectsMalformedToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> tokenService.digest("not-a-qr"));
        assertThatIllegalArgumentException().isThrownBy(() -> tokenService.digest(null));
    }
}
