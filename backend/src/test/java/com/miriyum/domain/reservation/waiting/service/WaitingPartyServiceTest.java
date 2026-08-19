package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.ExpectedVersionRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationAcceptanceRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.TransferProposalRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingPartyInvitation;
import com.miriyum.domain.reservation.waiting.entity.WaitingPartyAudit;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingRepresentativeTransferOffer;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingPartyAuditRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingPartyInvitationRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingRepresentativeTransferOfferRepository;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

class WaitingPartyServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T03:00:00Z");
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440501";
    private final ConsumerAccountService accounts = mock(ConsumerAccountService.class);
    private final WaitingTeamRepository teams = mock(WaitingTeamRepository.class);
    private final WaitingActiveMembershipRepository memberships =
            mock(WaitingActiveMembershipRepository.class);
    private final WaitingPartyInvitationRepository invitations =
            mock(WaitingPartyInvitationRepository.class);
    private final WaitingPartyAuditRepository audits = mock(WaitingPartyAuditRepository.class);
    private final WaitingRepresentativeTransferOfferRepository transfers =
            mock(WaitingRepresentativeTransferOfferRepository.class);
    private final IdempotencyExecutor idempotency = mock(IdempotencyExecutor.class);
    private final WaitingCreationTransactionExecutor transactions =
            mock(WaitingCreationTransactionExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WaitingPartyService service;

    @BeforeEach
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            BusinessResult<?> result = ((Supplier<BusinessResult<?>>) invocation.getArgument(1)).get();
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), objectMapper.valueToTree(result.data()));
        });
        service = new WaitingPartyService(
                accounts, teams, memberships, invitations, transfers, audits, idempotency,
                transactions, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void invitationIssueReturnsRawCodeButIdempotencyPayloadStoresMetadataOnly() {
        WaitingTeam team = team(2);
        when(teams.findByIdForUpdate(300L)).thenReturn(Optional.of(team));
        AtomicReference<WaitingPartyInvitation> saved = new AtomicReference<>();
        when(invitations.save(any())).thenAnswer(invocation -> {
            WaitingPartyInvitation invitation = invocation.getArgument(0);
            ReflectionTestUtils.setField(invitation, "id", 701L);
            saved.set(invitation);
            return invitation;
        });

        var result = service.issueInvitation(
                200L, 300L, IdempotencyKey.parse(KEY), new ExpectedVersionRequest(0L));

        assertThat(result.data().invitationCode()).isNotBlank();
        assertThat(result.data().invitationId()).isEqualTo("701");
        assertThat(saved.get().getTokenHash()).hasSize(64)
                .doesNotContain(result.data().invitationCode());
    }

    @Test
    void acceptingInvitationAddsMembershipToExistingTeamWithoutCreatingAnotherTeam() {
        WaitingTeam team = team(2);
        String rawCode = "invite-once-409";
        WaitingPartyInvitation invitation = WaitingPartyInvitation.issue(
                300L, 200L, sha256(rawCode), 0L, NOW.minusSeconds(1), NOW.plusSeconds(899));
        ReflectionTestUtils.setField(invitation, "id", 701L);
        WaitingActiveMembership representative = membership(401L, 200L, NOW.minusSeconds(60));
        AtomicReference<WaitingActiveMembership> joined = new AtomicReference<>();
        when(invitations.findByTokenHash(sha256(rawCode)))
                .thenReturn(Optional.of(invitation));
        when(invitations.findByIdForUpdate(701L)).thenReturn(Optional.of(invitation));
        when(teams.findByIdForUpdate(300L)).thenReturn(Optional.of(team));
        when(memberships.findByConsumerAccountId(201L)).thenReturn(Optional.empty());
        when(memberships.countByWaitingTeamId(300L)).thenReturn(1L);
        when(memberships.saveAndFlush(any())).thenAnswer(invocation -> {
            WaitingActiveMembership value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 402L);
            joined.set(value);
            return value;
        });
        when(memberships.findAllByWaitingTeamIdOrderById(300L)).thenAnswer(invocation ->
                List.of(representative, joined.get()));

        var result = service.acceptInvitation(
                201L, IdempotencyKey.parse(KEY), new InvitationAcceptanceRequest(rawCode));

        assertThat(result.data().waitingTeamId()).isEqualTo("300");
        assertThat(result.data().memberships()).hasSize(2);
        assertThat(result.data().version()).isOne();
        assertThat(invitation.getStatus()).isEqualTo(WaitingPartyInvitation.Status.ACCEPTED);
        verify(teams, never()).save(any());
    }

    @Test
    void proposalKeepsRepresentativeAndAcceptanceTransfersExactlyOnce() {
        WaitingTeam team = team(2);
        WaitingActiveMembership target = membership(402L, 201L, NOW.minusSeconds(30));
        when(teams.findByIdForUpdate(300L)).thenReturn(Optional.of(team));
        when(memberships.findByIdAndWaitingTeamId(402L, 300L)).thenReturn(Optional.of(target));
        when(transfers.findByActiveTeamKey(300L)).thenReturn(Optional.empty());
        AtomicReference<WaitingRepresentativeTransferOffer> saved = new AtomicReference<>();
        when(transfers.save(any())).thenAnswer(invocation -> {
            WaitingRepresentativeTransferOffer offer = invocation.getArgument(0);
            ReflectionTestUtils.setField(offer, "id", 801L);
            saved.set(offer);
            return offer;
        });

        var proposed = service.proposeTransfer(200L, 300L, IdempotencyKey.parse(KEY),
                new TransferProposalRequest(402L, 0L));

        assertThat(team.getConsumerAccountId()).isEqualTo(200L);
        assertThat(team.getVersion()).isZero();
        assertThat(proposed.data().expiresAt()).isEqualTo(NOW.plusSeconds(300));

        when(transfers.findByIdForUpdate(801L)).thenReturn(Optional.of(saved.get()));
        when(memberships.findAllByWaitingTeamIdOrderById(300L)).thenReturn(List.of(
                membership(401L, 200L, NOW.minusSeconds(60)), target));

        var accepted = service.acceptTransfer(201L, 300L, 801L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440502"),
                new ExpectedVersionRequest(0L));

        assertThat(team.getConsumerAccountId()).isEqualTo(201L);
        assertThat(team.getVersion()).isOne();
        assertThat(accepted.data().memberships()).hasSize(2);
        assertThat(saved.get().getStatus())
                .isEqualTo(WaitingRepresentativeTransferOffer.Status.ACCEPTED);
    }

    @Test
    void expiredTransferIsClosedAndAuditedBeforeReplacementProposal() {
        WaitingTeam team = team(2);
        WaitingActiveMembership target = membership(402L, 201L, NOW.minusSeconds(30));
        WaitingRepresentativeTransferOffer expired = WaitingRepresentativeTransferOffer.propose(
                300L, 200L, 402L, 0L, NOW.minusSeconds(300), NOW);
        ReflectionTestUtils.setField(expired, "id", 800L);
        List<WaitingPartyAudit> savedAudits = new ArrayList<>();
        when(teams.findByIdForUpdate(300L)).thenReturn(Optional.of(team));
        when(memberships.findByIdAndWaitingTeamId(402L, 300L)).thenReturn(Optional.of(target));
        when(transfers.findByActiveTeamKey(300L)).thenReturn(Optional.of(expired));
        when(transfers.save(any())).thenAnswer(invocation -> {
            WaitingRepresentativeTransferOffer offer = invocation.getArgument(0);
            ReflectionTestUtils.setField(offer, "id", 801L);
            return offer;
        });
        when(audits.save(any())).thenAnswer(invocation -> {
            WaitingPartyAudit audit = invocation.getArgument(0);
            savedAudits.add(audit);
            return audit;
        });

        var replacement = service.proposeTransfer(
                200L, 300L, IdempotencyKey.parse(KEY),
                new TransferProposalRequest(402L, 0L));

        assertThat(expired.getStatus())
                .isEqualTo(WaitingRepresentativeTransferOffer.Status.EXPIRED);
        assertThat(expired.getActiveTeamKey()).isNull();
        assertThat(expired.getDecidedAt()).isEqualTo(NOW);
        assertThat(replacement.data().offerId()).isEqualTo("801");
        assertThat(savedAudits).extracting(audit -> ReflectionTestUtils.getField(
                        audit, "eventType"))
                .containsExactly(
                        WaitingPartyAudit.EventType.REPRESENTATIVE_TRANSFER_EXPIRED,
                        WaitingPartyAudit.EventType.REPRESENTATIVE_TRANSFER_PROPOSED);
        assertThat(savedAudits).extracting(audit -> ReflectionTestUtils.getField(
                        audit, "commandId"))
                .doesNotHaveDuplicates();
    }

    @Test
    void concurrentAccountMembershipUniqueViolationMapsToWaiting011() {
        WaitingTeam team = team(2);
        String rawCode = "concurrent-account-409";
        WaitingPartyInvitation invitation = WaitingPartyInvitation.issue(
                300L, 200L, sha256(rawCode), 0L, NOW.minusSeconds(1), NOW.plusSeconds(899));
        ReflectionTestUtils.setField(invitation, "id", 701L);
        when(invitations.findByTokenHash(sha256(rawCode))).thenReturn(Optional.of(invitation));
        when(invitations.findByIdForUpdate(701L)).thenReturn(Optional.of(invitation));
        when(teams.findByIdForUpdate(300L)).thenReturn(Optional.of(team));
        when(memberships.findByConsumerAccountId(201L)).thenReturn(Optional.empty());
        when(memberships.countByWaitingTeamId(300L)).thenReturn(1L);
        when(memberships.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry for key 'uk_waiting_active_memberships_consumer_account'"));

        assertThatThrownBy(() -> service.acceptInvitation(201L, IdempotencyKey.parse(KEY),
                new InvitationAcceptanceRequest(rawCode)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS));
    }

    private static WaitingTeam team(int partySize) {
        WaitingTeam team = WaitingTeam.create(
                100L, 200L, LocalDate.of(2026, 8, 19), partySize,
                WaitingSource.REMOTE, 1L, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(team, "id", 300L);
        return team;
    }

    private static WaitingActiveMembership membership(long id, long accountId, Instant joinedAt) {
        WaitingActiveMembership value = WaitingActiveMembership.create(100L, accountId, 300L, joinedAt);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
