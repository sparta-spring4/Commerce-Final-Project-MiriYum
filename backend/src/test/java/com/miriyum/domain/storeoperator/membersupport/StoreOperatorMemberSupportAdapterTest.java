package com.miriyum.domain.storeoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class StoreOperatorMemberSupportAdapterTest {
    private StoreOperatorMemberSupportRepository repository;
    private RefreshTokenManager refreshTokens;
    private PasswordEncoder encoder;
    private StoreOperatorMemberSupportAdapter adapter;
    private StoreOperatorAccount account;

    @BeforeEach
    void setUp() {
        repository = mock(StoreOperatorMemberSupportRepository.class);
        refreshTokens = mock(RefreshTokenManager.class);
        encoder = mock(PasswordEncoder.class);
        adapter = new StoreOperatorMemberSupportAdapter(
                repository, new PasswordPolicy(), encoder, refreshTokens);
        account = StoreOperatorAccount.createWithContact(
                "old-owner@example.com", "old-hash", "owner", "+821087654321");
        ReflectionTestUtils.setField(account, "id", 71L);
        ReflectionTestUtils.setField(account, "createdAt", LocalDateTime.of(2026, 8, 2, 9, 0));
    }

    @Test
    void routesStoreOperatorSnapshotWithoutContactOrCredentialData() {
        when(repository.findById(71L)).thenReturn(Optional.of(account));

        var snapshot = adapter.findMinimal(71L).orElseThrow();

        assertThat(snapshot.accountType()).isEqualTo(MemberAccountType.STORE_OPERATOR);
        assertThat(snapshot.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("email", "phone", "passwordHash", "displayName");
    }

    @Test
    void recoveryAndSuspensionMutationsUseOneSupportVersionAndRevokeSessions() {
        when(repository.findByIdForUpdate(71L)).thenReturn(Optional.of(account));

        assertThat(adapter.approveRecovery(71L, 0, "new-owner@example.com")).isEqualTo(1);
        assertThat(adapter.applySuspension(71L, 1)).isEqualTo(2);
        assertThat(account.getStatus()).isEqualTo(StoreOperatorAccountStatus.SUSPENDED);
        assertThat(adapter.clearSuspension(71L, 2)).isEqualTo(3);
        assertThat(account.getStatus()).isEqualTo(StoreOperatorAccountStatus.ACTIVE);
        verify(refreshTokens, org.mockito.Mockito.times(2))
                .revokeAll(TokenNamespace.STORE_OPERATOR, 71L);
    }

    @Test
    void recoveredPasswordClearsRequirementAndRevokesSessions() {
        account.approveRecovery("new-owner@example.com");
        when(repository.findByIdForUpdate(71L)).thenReturn(Optional.of(account));
        when(encoder.encode("NewPassword1!")).thenReturn("new-hash");

        adapter.replaceRecoveredPassword(71L, "NewPassword1!");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isPasswordResetRequired()).isFalse();
        verify(refreshTokens).revokeAll(TokenNamespace.STORE_OPERATOR, 71L);
    }
}
