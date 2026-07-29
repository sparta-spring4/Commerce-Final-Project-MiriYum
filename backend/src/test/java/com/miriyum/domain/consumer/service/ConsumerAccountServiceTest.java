package com.miriyum.domain.consumer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.consumer.dto.request.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.response.ConsumerAccountResponse;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsumerAccountServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private ConsumerAccountRepository consumerAccountRepository;

    private final NicknamePolicy nicknamePolicy = new NicknamePolicy();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);

    private ConsumerAccountService consumerAccountService;

    @BeforeEach
    void setUp() {
        consumerAccountService = new ConsumerAccountService(consumerAccountRepository, nicknamePolicy, clock);
    }

    @Test
    @DisplayName("닉네임을 한 번도 바꾼 적 없으면 바로 변경할 수 있다")
    void allowsFirstNicknameChange() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "010-1234-5678", "이전닉네임");
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when
        ConsumerAccountResponse response =
                consumerAccountService.updateName(ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임"));

        // then
        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(account.getNicknameChangedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    @DisplayName("7일 이내에 다시 바꾸려 하면 ACCOUNT_005를 던진다")
    void rejectsNicknameChangeWithinCooldown() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "010-1234-5678", "이전닉네임");
        account.changeName("이전닉네임", LocalDateTime.now(clock).minusDays(3));
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() ->
                consumerAccountService.updateName(ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AccountErrorCode.NICKNAME_CHANGE_TOO_SOON);
    }

    @Test
    @DisplayName("7일 이내면 닉네임 형식이 잘못됐어도 형식 오류가 아니라 ACCOUNT_005를 던진다")
    void cooldownCheckTakesPriorityOverFormatValidation() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "010-1234-5678", "이전닉네임");
        account.changeName("이전닉네임", LocalDateTime.now(clock).minusDays(3));
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() ->
                consumerAccountService.updateName(ACCOUNT_ID, new ConsumerAccountUpdateRequest("!")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AccountErrorCode.NICKNAME_CHANGE_TOO_SOON);
    }

    @Test
    @DisplayName("7일이 지나면 다시 바꿀 수 있다")
    void allowsNicknameChangeAfterCooldown() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "010-1234-5678", "이전닉네임");
        account.changeName("이전닉네임", LocalDateTime.now(clock).minusDays(8));
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when
        ConsumerAccountResponse response =
                consumerAccountService.updateName(ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임"));

        // then
        assertThat(response.nickname()).isEqualTo("새닉네임");
    }
}
