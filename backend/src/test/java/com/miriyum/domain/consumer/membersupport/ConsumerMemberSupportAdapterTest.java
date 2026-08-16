package com.miriyum.domain.consumer.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class ConsumerMemberSupportAdapterTest {
    private ConsumerMemberSupportRepository repository;
    private RefreshTokenManager refreshTokens;
    private PasswordEncoder encoder;
    private ConsumerMemberSupportAdapter adapter;
    private ConsumerAccount account;

    @BeforeEach
    void setUp() {
        repository = mock(ConsumerMemberSupportRepository.class);
        refreshTokens = mock(RefreshTokenManager.class);
        encoder = mock(PasswordEncoder.class);
        adapter = new ConsumerMemberSupportAdapter(
                repository, new PasswordPolicy(), encoder, refreshTokens);
        account = ConsumerAccount.createWithContact(
                "old@example.com", "old-hash", "consumer", "+821012345678", "contact-ref");
        ReflectionTestUtils.setField(account, "id", 41L);
        ReflectionTestUtils.setField(account, "createdAt", LocalDateTime.of(2026, 8, 1, 9, 0));
    }

    @Test
    void minimalSnapshotContainsNoContactOrCredentialField() {
        when(repository.findById(41L)).thenReturn(Optional.of(account));

        var snapshot = adapter.findMinimal(41L).orElseThrow();

        assertThat(snapshot.accountType()).isEqualTo(MemberAccountType.CONSUMER);
        assertThat(snapshot.accountId()).isEqualTo(41L);
        assertThat(snapshot.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("email", "phone", "passwordHash", "reservationContactReference");
    }

    @Test
    void recoveryApprovalUsesLockedVersionAndRevokesEveryRefreshSession() {
        when(repository.findByIdForUpdate(41L)).thenReturn(Optional.of(account));

        long nextVersion = adapter.approveRecovery(41L, 0, "new@example.com");

        assertThat(nextVersion).isEqualTo(1L);
        assertThat(account.getEmail()).isEqualTo("new@example.com");
        assertThat(account.isPasswordResetRequired()).isTrue();
        verify(refreshTokens).revokeAll(TokenNamespace.CONSUMER, 41L);
    }

    @Test
    void recoveredPasswordIsNormalizedEncodedAndDoesNotIssueTokens() {
        account.approveRecovery("new@example.com");
        when(repository.findByIdForUpdate(41L)).thenReturn(Optional.of(account));
        when(encoder.encode("NewPassword1!")).thenReturn("new-hash");

        adapter.replaceRecoveredPassword(41L, "NewPassword1!");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isPasswordResetRequired()).isFalse();
        verify(refreshTokens).revokeAll(TokenNamespace.CONSUMER, 41L);
    }

    @Test
    void suspensionTransitionsUseTheSameAccountGuard() {
        when(repository.findByIdForUpdate(41L)).thenReturn(Optional.of(account));

        assertThat(adapter.applySuspension(41L, 0)).isEqualTo(1L);
        assertThat(account.getStatus()).isEqualTo(ConsumerAccountStatus.SUSPENDED);
        assertThat(adapter.clearSuspension(41L, 1)).isEqualTo(2L);
        assertThat(account.getStatus()).isEqualTo(ConsumerAccountStatus.ACTIVE);
    }
}
