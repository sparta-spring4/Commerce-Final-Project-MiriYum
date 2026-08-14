package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuthorizationService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportQueryService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemberSupportQueryServiceTest {
    private static final PlatformOperatorPrincipal PRINCIPAL =
            new PlatformOperatorPrincipal(9L, "admin@example.com", "session", 2, 3, false);

    @Test
    void permissionIsCheckedBeforeAnyAccountLookup() {
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        when(authorities.requireCurrentAuthority(9, 2))
                .thenReturn(new OperatorAuthority(9, 2, Set.of(), Set.of()));
        MemberAccountSupportRegistry accounts = mock(MemberAccountSupportRegistry.class);
        MemberSupportQueryService service = new MemberSupportQueryService(
                new MemberSupportAuthorizationService(authorities), accounts);

        assertThatThrownBy(() -> service.get(PRINCIPAL, MemberAccountType.CONSUMER, 41))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
        verify(accounts, never()).require(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wrongTypeIdIsHiddenAndOtherTypePortIsNeverProbed() {
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        when(authorities.requireCurrentAuthority(9, 2)).thenReturn(new OperatorAuthority(
                9, 2, Set.of(), Set.of(PlatformOperatorPermission.MEMBER_READ_MINIMAL)));
        MemberAccountSupportPort consumer = mock(MemberAccountSupportPort.class);
        MemberAccountSupportPort store = mock(MemberAccountSupportPort.class);
        when(consumer.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(store.accountType()).thenReturn(MemberAccountType.STORE_OPERATOR);
        when(consumer.findMinimal(71)).thenReturn(Optional.empty());
        when(store.findMinimal(71)).thenReturn(Optional.of(new MemberAccountSnapshot(
                MemberAccountType.STORE_OPERATOR, 71, false, false, Instant.now(), 0)));
        MemberSupportQueryService service = new MemberSupportQueryService(
                new MemberSupportAuthorizationService(authorities),
                new MemberAccountSupportRegistry(List.of(consumer, store)));

        assertThatThrownBy(() -> service.get(PRINCIPAL, MemberAccountType.CONSUMER, 71))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        verify(store, never()).findMinimal(71);
    }
}
