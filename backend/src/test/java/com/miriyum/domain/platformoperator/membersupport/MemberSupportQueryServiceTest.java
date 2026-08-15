package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountPage;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.exception.AdminAuthorizationErrorCode;
import com.miriyum.domain.platformoperator.service.OperatorAuthorityReader;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuthorizationService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportQueryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberProjectionReader;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
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
                new MemberSupportAuthorizationService(authorities), accounts,
                mock(MemberSanctionRepository.class), Clock.systemUTC(), mock(MemberProjectionReader.class));

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
                new MemberAccountSupportRegistry(List.of(consumer, store)),
                mock(MemberSanctionRepository.class), Clock.systemUTC(), mock(MemberProjectionReader.class));

        assertThatThrownBy(() -> service.get(PRINCIPAL, MemberAccountType.CONSUMER, 71))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
        verify(store, never()).findMinimal(71);
    }

    @Test
    void statusFilteringKeepsAccurateTotalAndNewestFirstAcrossAccountTypes() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        when(authorities.requireCurrentAuthority(9, 2)).thenReturn(new OperatorAuthority(
                9, 2, Set.of(), Set.of(PlatformOperatorPermission.MEMBER_READ_MINIMAL)));
        MemberAccountSupportPort consumer = mock(MemberAccountSupportPort.class);
        MemberAccountSupportPort store = mock(MemberAccountSupportPort.class);
        when(consumer.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(store.accountType()).thenReturn(MemberAccountType.STORE_OPERATOR);
        MemberSearchCriteria criteria = new MemberSearchCriteria(null, null);
        MemberProjectionReader projections = mock(MemberProjectionReader.class);
        when(projections.read(null, MemberStatus.TEMPORARILY_SUSPENDED, criteria, 0, 20,
                LocalDateTime.now(clock)))
                .thenReturn(new MemberProjectionReader.Page(List.of(
                        new MemberProjectionReader.Row(MemberAccountType.STORE_OPERATOR, 3,
                                Instant.parse("2026-01-04T00:00:00Z"), 0,
                                MemberStatus.TEMPORARILY_SUSPENDED),
                        new MemberProjectionReader.Row(MemberAccountType.CONSUMER, 2,
                                Instant.parse("2026-01-02T00:00:00Z"), 0,
                                MemberStatus.TEMPORARILY_SUSPENDED)), 2));
        MemberSupportQueryService service = new MemberSupportQueryService(
                new MemberSupportAuthorizationService(authorities),
                new MemberAccountSupportRegistry(List.of(consumer, store)),
                mock(MemberSanctionRepository.class), clock, projections);

        var result = service.list(PRINCIPAL, null, MemberStatus.TEMPORARILY_SUSPENDED,
                criteria, 0, 20);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content()).extracting(response -> response.accountId()).containsExactly(3L, 2L);
    }

    @Test
    void activeSanctionLedgerDrivesFeatureAndPermanentProjectionAndFiltering() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        OperatorAuthorityReader authorities = mock(OperatorAuthorityReader.class);
        when(authorities.requireCurrentAuthority(9, 2)).thenReturn(new OperatorAuthority(
                9, 2, Set.of(), Set.of(PlatformOperatorPermission.MEMBER_READ_MINIMAL)));
        MemberAccountSupportPort consumer = mock(MemberAccountSupportPort.class);
        when(consumer.accountType()).thenReturn(MemberAccountType.CONSUMER);
        MemberSearchCriteria criteria = new MemberSearchCriteria(null, null);
        MemberAccountSnapshot featureAccount = snapshot(
                MemberAccountType.CONSUMER, 41, false, "2026-01-02T00:00:00Z");
        MemberSupportCase enforcement = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 41, 0, "ABUSE", LocalDateTime.now(clock));
        MemberSanction feature = MemberSanction.propose(enforcement, MemberSanctionLevel.FEATURE_RESTRICTION,
                Set.of(RestrictedFeature.RESERVATION), "ABUSE", "v1", 9, LocalDateTime.now(clock));
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        when(sanctions.findActive(MemberAccountType.CONSUMER, 41, LocalDateTime.now(clock)))
                .thenReturn(List.of(feature));
        MemberProjectionReader projections = mock(MemberProjectionReader.class);
        when(projections.read(MemberAccountType.CONSUMER, MemberStatus.FEATURE_RESTRICTED,
                criteria, 0, 20, LocalDateTime.now(clock)))
                .thenReturn(new MemberProjectionReader.Page(List.of(new MemberProjectionReader.Row(
                        MemberAccountType.CONSUMER, 41, featureAccount.joinedAt(), 0,
                        MemberStatus.FEATURE_RESTRICTED)), 1));
        MemberSupportQueryService service = new MemberSupportQueryService(
                new MemberSupportAuthorizationService(authorities),
                new MemberAccountSupportRegistry(List.of(consumer)), sanctions, clock, projections);

        var result = service.list(PRINCIPAL, MemberAccountType.CONSUMER, MemberStatus.FEATURE_RESTRICTED,
                criteria, 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().getFirst().status()).isEqualTo(MemberStatus.FEATURE_RESTRICTED);
        assertThat(result.content().getFirst().activeSanctions()).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.level()).isEqualTo(MemberSanctionLevel.FEATURE_RESTRICTION);
                    assertThat(summary.restrictedFeatures()).containsExactly(RestrictedFeature.RESERVATION);
                });

        MemberAccountSnapshot permanentAccount = snapshot(
                MemberAccountType.CONSUMER, 42, true, "2026-01-03T00:00:00Z");
        when(consumer.findMinimal(42)).thenReturn(Optional.of(permanentAccount));
        MemberSupportCase permanentCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 42, 0, "SEVERE", LocalDateTime.now(clock));
        MemberSanction permanent = MemberSanction.propose(permanentCase,
                MemberSanctionLevel.PERMANENT_SUSPENSION, Set.of(), "SEVERE", "v1", 9,
                LocalDateTime.now(clock));
        permanent.approvePermanent(10, LocalDateTime.now(clock));
        when(sanctions.findActive(MemberAccountType.CONSUMER, 42, LocalDateTime.now(clock)))
                .thenReturn(List.of(permanent));
        assertThat(service.get(PRINCIPAL, MemberAccountType.CONSUMER, 42).status())
                .isEqualTo(MemberStatus.PERMANENTLY_SUSPENDED);
    }

    private MemberAccountSnapshot snapshot(MemberAccountType type, long id, boolean suspended, String joinedAt) {
        return new MemberAccountSnapshot(type, id, false, suspended, Instant.parse(joinedAt), 0);
    }
}
