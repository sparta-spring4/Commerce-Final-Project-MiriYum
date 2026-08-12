package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoSignUpTicket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoSignUpTicketServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
    private final KakaoSignUpTicketService ticketService = new KakaoSignUpTicketService(
            "kakao-sign-up-ticket-test-secret-must-be-long-enough", "miriyum", clock);

    @Test
    @DisplayName("카카오 가입 티켓은 계정 유형과 카카오 식별자 fingerprint만 보존한다")
    void createsShortLivedTicketWithoutRawKakaoSubject() {
        String ticket = ticketService.create(TokenNamespace.CONSUMER, "v1", "fingerprint");

        KakaoSignUpTicket parsed = ticketService.parse(ticket);

        assertThat(parsed.namespace()).isEqualTo(TokenNamespace.CONSUMER);
        assertThat(parsed.fingerprintKeyVersion()).isEqualTo("v1");
        assertThat(parsed.providerSubjectFingerprint()).isEqualTo("fingerprint");
        assertThat(ticket).doesNotContain("kakao-subject");
    }
}
