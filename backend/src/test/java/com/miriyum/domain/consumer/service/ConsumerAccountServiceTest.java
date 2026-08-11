package com.miriyum.domain.consumer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.consumer.dto.account.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.account.ConsumerContactRegistrationRequest;
import com.miriyum.domain.consumer.dto.account.ConsumerAccountResponse;
import com.miriyum.domain.consumer.dto.contract.ReservationContactResult;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConsumerAccountServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    private static final IdempotencyCommand UPDATE_COMMAND = new IdempotencyCommand(
            "consumer", ACCOUNT_ID, "CONSUMER_ACCOUNT_UPDATE",
            "123e4567-e89b-12d3-a456-426614174000", "a".repeat(64));

    private static final IdempotencyCommand CONTACT_COMMAND = new IdempotencyCommand(
            "consumer", ACCOUNT_ID, "CONSUMER_CONTACT_REGISTER",
            "123e4567-e89b-12d3-a456-426614174001", "b".repeat(64));

    @Mock
    private ConsumerAccountRepository consumerAccountRepository;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    @Mock
    private EntityManager entityManager;

    private final NicknamePolicy nicknamePolicy = new NicknamePolicy();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);

    private ConsumerAccountService consumerAccountService;

    @BeforeEach
    void setUp() {
        consumerAccountService = new ConsumerAccountService(
                consumerAccountRepository, nicknamePolicy, clock, idempotencyExecutor,
                new PhoneNumberPolicy(), new ReservationContactReferenceGenerator());
        ReflectionTestUtils.setField(consumerAccountService, "entityManager", entityManager);
    }

    @Test
    @DisplayName("존재하지 않는 계정을 조회하면 C-013에 따라 401 AUTH_003을 던진다")
    void rejectsUnknownAccount() {
        // given
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> consumerAccountService.getMe(ACCOUNT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("비활성 계정을 조회하면 계정 부재와 달리 403 AUTH_011을 던진다")
    void rejectsSuspendedAccount() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        ReflectionTestUtils.setField(account, "status", ConsumerAccountStatus.SUSPENDED);
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() -> consumerAccountService.getMe(ACCOUNT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
    }

    @Test
    @DisplayName("계정 부재와 비활성 계정은 서로 다른 상태 코드로 갈린다")
    void separatesMissingAccountFromSuspendedAccount() {
        assertThat(AuthErrorCode.ACCESS_TOKEN_INVALID.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AuthErrorCode.ACCOUNT_RESTRICTED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("전화번호가 없는 기존 계정은 최초 연락처 등록 시 예약 참조를 함께 만든다")
    void registersFirstContactAndReservationReference() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(consumerAccountRepository.findByIdForUpdate(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        consumerAccountService.registerContact(
                CONTACT_COMMAND, ACCOUNT_ID, new ConsumerContactRegistrationRequest("010-1234-5678"));

        // then
        assertThat(account.getPhone()).isEqualTo("01012345678");
        assertThat(account.getReservationContactReference()).isNotBlank();
        assertThat(businessResult.get().data())
                .isInstanceOfSatisfying(ConsumerAccountResponse.class,
                        response -> assertThat(response.phoneNumber()).isEqualTo("010-****-5678"));
    }

    @Test
    @DisplayName("기존 연락처의 공백·하이픈 형식이 달라도 같은 번호로 참조를 확정한다")
    void normalizesExistingContactBeforeComparison() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        account.registerContact("010-1234-5678", null);
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(consumerAccountRepository.findByIdForUpdate(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        consumerAccountService.registerContact(
                CONTACT_COMMAND, ACCOUNT_ID, new ConsumerContactRegistrationRequest("01012345678"));

        // then
        assertThat(account.getPhone()).isEqualTo("01012345678");
        assertThat(account.getReservationContactReference()).isNotBlank();
        assertThat(businessResult.get().data()).isInstanceOf(ConsumerAccountResponse.class);
    }

    @Test
    @DisplayName("최초 등록 뒤 다른 전화번호로 변경하면 ACCOUNT_007을 던진다")
    void rejectsChangingRegisteredContact() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        account.registerContact("01012345678", "reference-1");
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(consumerAccountRepository.findByIdForUpdate(ACCOUNT_ID)).willReturn(Optional.of(account));
        runBusinessWorkOnExecute();

        // when & then
        assertThatThrownBy(() -> consumerAccountService.registerContact(
                CONTACT_COMMAND, ACCOUNT_ID, new ConsumerContactRegistrationRequest("010-9999-9999")))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AccountErrorCode.CONTACT_CHANGE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("전화번호가 없는 활성 계정으로 예약 연락처를 요청하면 ACCOUNT_006을 던진다")
    void rejectsReservationContactWhenPhoneIsMissing() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() -> consumerAccountService.getReservationContact(ACCOUNT_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AccountErrorCode.RESERVATION_CONTACT_REQUIRED));
    }

    @Test
    @DisplayName("전화번호는 있지만 예약 참조가 없으면 ACCOUNT_006을 던진다")
    void rejectsReservationContactWhenReferenceIsMissing() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        account.registerContact("01012345678", null);
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() -> consumerAccountService.getReservationContact(ACCOUNT_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AccountErrorCode.RESERVATION_CONTACT_REQUIRED));
    }

    @Test
    @DisplayName("예약 연락처 결과는 전화번호 원문 대신 opaque reference를 반환한다")
    void returnsReservationContactReference() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "닉네임");
        account.registerContact("01012345678", "reference-1");
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when
        ReservationContactResult result = consumerAccountService.getReservationContact(ACCOUNT_ID);

        // then
        assertThat(result.notificationTargetReference()).isEqualTo("reference-1");
        assertThat(result.contactAvailable()).isTrue();
    }

    @Test
    @DisplayName("계정이 없으면 멱등 실행기를 호출하기 전에 401 AUTH_003으로 거절한다")
    void rejectsUpdateBeforeIdempotentExecutionWhenAccountMissing() {
        // given
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> consumerAccountService.updateName(
                UPDATE_COMMAND, ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.ACCESS_TOKEN_INVALID);

        // 재생 경로는 업무 콜백을 실행하지 않으므로, 계정 확인이 콜백 안에 있으면 저장된 200이
        // 그대로 나간다. 실행기에 들어가기 전에 막혔는지를 호출 자체로 고정한다.
        then(idempotencyExecutor).should(never()).execute(any(), any());
    }

    @Test
    @DisplayName("닉네임을 한 번도 바꾼 적 없으면 바로 변경할 수 있다")
    void allowsFirstNicknameChange() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "이전닉네임");
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        consumerAccountService.updateName(UPDATE_COMMAND, ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임"));

        // then
        assertThat(businessResult.get().data())
                .isInstanceOfSatisfying(ConsumerAccountResponse.class,
                        response -> assertThat(response.nickname()).isEqualTo("새닉네임"));
        assertThat(account.getNicknameChangedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    @DisplayName("7일 이내에 다시 바꾸려 하면 ACCOUNT_005를 던진다")
    void rejectsNicknameChangeWithinCooldown() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "이전닉네임");
        account.changeName("이전닉네임", LocalDateTime.now(clock).minusDays(3));
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        runBusinessWorkOnExecute();

        // when & then
        assertThatThrownBy(() -> consumerAccountService.updateName(
                UPDATE_COMMAND, ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AccountErrorCode.NICKNAME_CHANGE_TOO_SOON);
    }

    @Test
    @DisplayName("7일 이내면 닉네임 형식이 잘못됐어도 형식 오류가 아니라 ACCOUNT_005를 던진다")
    void cooldownCheckTakesPriorityOverFormatValidation() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "이전닉네임");
        account.changeName("이전닉네임", LocalDateTime.now(clock).minusDays(3));
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        runBusinessWorkOnExecute();

        // when & then
        assertThatThrownBy(() -> consumerAccountService.updateName(
                UPDATE_COMMAND, ACCOUNT_ID, new ConsumerAccountUpdateRequest("!")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AccountErrorCode.NICKNAME_CHANGE_TOO_SOON);
    }

    @Test
    @DisplayName("7일이 지나면 다시 바꿀 수 있다")
    void allowsNicknameChangeAfterCooldown() {
        // given
        ConsumerAccount account = ConsumerAccount.create("user@example.com", "hashed", "이전닉네임");
        account.changeName("이전닉네임", LocalDateTime.now(clock).minusDays(8));
        given(consumerAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        AtomicReference<BusinessResult<?>> businessResult = runBusinessWorkOnExecute();

        // when
        consumerAccountService.updateName(UPDATE_COMMAND, ACCOUNT_ID, new ConsumerAccountUpdateRequest("새닉네임"));

        // then
        assertThat(businessResult.get().data())
                .isInstanceOfSatisfying(ConsumerAccountResponse.class,
                        response -> assertThat(response.nickname()).isEqualTo("새닉네임"));
    }

    /**
     * 실제 {@code IdempotencyExecutor}는 DB 선점이 필요하므로, 단위 테스트에서는 업무 콜백만
     * 그대로 실행하고 그 결과를 꺼내볼 수 있게 대역을 세운다(신규 선점 경로와 같다).
     */
    private AtomicReference<BusinessResult<?>> runBusinessWorkOnExecute() {
        AtomicReference<BusinessResult<?>> captured = new AtomicReference<>();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> businessWork = invocation.getArgument(1);
            BusinessResult<?> result = businessWork.get();
            captured.set(result);
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(), null);
        });
        return captured;
    }
}
