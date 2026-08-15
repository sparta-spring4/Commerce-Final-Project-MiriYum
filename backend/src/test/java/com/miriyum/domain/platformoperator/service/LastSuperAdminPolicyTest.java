package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthorityGuard;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthorityGuardRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorRoleGrantRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LastSuperAdminPolicyTest {
    private PlatformOperatorRoleGrantRepository roles;
    private LastSuperAdminPolicy policy;

    @BeforeEach
    void setUp() {
        PlatformOperatorAuthorityGuardRepository guard = mock(PlatformOperatorAuthorityGuardRepository.class);
        roles = mock(PlatformOperatorRoleGrantRepository.class);
        when(guard.lockSingleton()).thenReturn(Optional.of(mock(PlatformOperatorAuthorityGuard.class)));
        policy = new LastSuperAdminPolicy(guard, roles);
    }

    @Test
    void allowsANonSuperAdministrator() {
        inTransaction(() -> assertThatCode(() -> policy.assertRemovable(7L)).doesNotThrowAnyException());
    }

    @Test
    void rejectsMutatingAnyActiveSuperAdministratorEvenIfLegacyDataContainsTwo() {
        when(roles.countActiveSuperAdministratorById(7L)).thenReturn(1L);
        when(roles.countActiveSuperAdministrators()).thenReturn(2L);
        inTransaction(() -> assertThatThrownBy(() -> policy.assertRemovable(7L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED)));
    }

    @Test
    void rejectsRemovingTheLastActiveSuperAdministrator() {
        when(roles.countActiveSuperAdministratorById(7L)).thenReturn(1L);
        when(roles.countActiveSuperAdministrators()).thenReturn(1L);
        inTransaction(() -> assertThatThrownBy(() -> policy.assertRemovable(7L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED)));
    }

    @Test
    void acceptsOnlyTheSingletonActiveSuperAdministratorAsManagementActor() {
        when(roles.countActiveSuperAdministratorById(7L)).thenReturn(1L);
        when(roles.countActiveSuperAdministrators()).thenReturn(1L);

        inTransaction(() -> assertThatCode(() -> policy.requireSingletonActor(7L))
                .doesNotThrowAnyException());
    }

    @Test
    void rejectsOrdinaryOperatorAsManagementActor() {
        when(roles.countActiveSuperAdministratorById(7L)).thenReturn(0L);
        when(roles.countActiveSuperAdministrators()).thenReturn(1L);

        inTransaction(() -> assertThatThrownBy(() -> policy.requireSingletonActor(7L))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED)));
    }

    @Test
    void failsClosedWhenActiveSuperAdministratorCardinalityIsInvalid() {
        when(roles.countActiveSuperAdministratorById(7L)).thenReturn(1L);
        when(roles.countActiveSuperAdministrators()).thenReturn(0L, 2L);

        inTransaction(() -> {
            assertThatThrownBy(() -> policy.requireSingletonActor(7L))
                    .isInstanceOf(ServiceException.class)
                    .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                            .isEqualTo(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED));
            assertThatThrownBy(() -> policy.requireSingletonActor(7L))
                    .isInstanceOf(ServiceException.class)
                    .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                            .isEqualTo(AdminAuthorizationErrorCode.LAST_SUPER_ADMIN_REQUIRED));
        });
    }

    private void inTransaction(Runnable assertion) {
        try (MockedStatic<TransactionSynchronizationManager> tx = mockStatic(TransactionSynchronizationManager.class)) {
            tx.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);
            assertion.run();
        }
    }
}
