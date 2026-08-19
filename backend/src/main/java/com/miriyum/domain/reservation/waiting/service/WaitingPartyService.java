package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.ExpectedVersionRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationAcceptanceRequest;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.InvitationSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.PartyCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.TransferCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.TransferOfferSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.TransferProposalRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingPartyAudit;
import com.miriyum.domain.reservation.waiting.entity.WaitingPartyAudit.EventType;
import com.miriyum.domain.reservation.waiting.entity.WaitingPartyInvitation;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingRepresentativeTransferOffer;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingPartyAuditRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingPartyInvitationRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingRepresentativeTransferOfferRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** WAITING 상태의 일행 초대, 합류, 이탈과 제거를 멱등 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class WaitingPartyService {

    private static final Duration INVITATION_TTL = Duration.ofMinutes(15);
    private static final Duration TRANSFER_TTL = Duration.ofMinutes(5);
    private final ConsumerAccountService accountService;
    private final WaitingTeamRepository teamRepository;
    private final WaitingActiveMembershipRepository membershipRepository;
    private final WaitingPartyInvitationRepository invitationRepository;
    private final WaitingRepresentativeTransferOfferRepository transferRepository;
    private final WaitingPartyAuditRepository auditRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final WaitingCreationTransactionExecutor transactionExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InvitationCommandResult issueInvitation(long accountId, long teamId,
            IdempotencyKey key, ExpectedVersionRequest request) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        IdempotencyCommand command = command(accountId, "WAITING_PARTY_INVITATION_ISSUE", key,
                "teamId=" + teamId + "|expectedVersion=" + request.expectedVersion());
        AtomicReference<String> freshCode = new AtomicReference<>();
        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    WaitingTeam team = lockTeam(teamId);
                    requireRepresentative(team, accountId);
                    team.requirePartyMutable(request.expectedVersion());
                    Instant now = clock.instant();
                    String rawCode = UUID.randomUUID().toString();
                    WaitingPartyInvitation invitation = invitationRepository.save(
                            WaitingPartyInvitation.issue(
                                    teamId, accountId, hash(rawCode), team.getVersion(), now,
                                    now.plus(INVITATION_TTL)));
                    freshCode.set(rawCode);
                    audit(team, accountId, null, EventType.INVITATION_ISSUED,
                            team.getVersion(), team.getVersion(), "INVITATION_ISSUED", command, now);
                    return success("WAITING_PARTY_INVITATION", invitation.getId(),
                            new InvitationSnapshot(
                                    Long.toString(invitation.getId()), invitation.getExpiresAt(), null));
                }));
        InvitationSnapshot stored = objectMapper.treeToValue(
                outcome.data(), InvitationSnapshot.class);
        return new InvitationCommandResult(
                outcome.httpStatus(), outcome.replayed() ? stored : stored.withFreshCode(freshCode.get()));
    }

    public PartyCommandResult acceptInvitation(long accountId, IdempotencyKey key,
            InvitationAcceptanceRequest request) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        String tokenHash = hash(request.invitationCode());
        IdempotencyCommand command = command(accountId, "WAITING_PARTY_INVITATION_ACCEPT", key,
                "tokenHash=" + tokenHash);
        IdempotentOutcome outcome;
        try {
            outcome = transactionExecutor.execute(() ->
                    idempotencyExecutor.execute(command, () -> {
                    WaitingPartyInvitation discovered = invitationRepository.findByTokenHash(tokenHash)
                            .orElseThrow(WaitingPartyService::invalidInvitation);
                    WaitingTeam team = lockTeam(discovered.getWaitingTeamId());
                    WaitingPartyInvitation invitation = invitationRepository
                            .findByIdForUpdate(discovered.getId())
                            .filter(candidate -> candidate.getTokenHash().equals(tokenHash))
                            .orElseThrow(WaitingPartyService::invalidInvitation);
                    Instant now = clock.instant();
                    try {
                        invitation.requireUsable(now);
                    } catch (IllegalStateException invalid) {
                        throw invalidInvitation();
                    }
                    team.requirePartyMutable(invitation.getIssuedTeamVersion());
                    if (membershipRepository.findByConsumerAccountId(accountId).isPresent()) {
                        throw new ServiceException(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS);
                    }
                    if (membershipRepository.countByWaitingTeamId(team.getId()) >= team.getPartySize()) {
                        throw new ServiceException(ReservationErrorCode.PARTY_CAPACITY_EXCEEDED);
                    }
                    WaitingActiveMembership membership = membershipRepository.saveAndFlush(
                            WaitingActiveMembership.create(
                                    team.getStoreId(), accountId, team.getId(), now));
                    team.partyChanged(invitation.getIssuedTeamVersion());
                    invitation.accept(accountId, now);
                    audit(team, accountId, membership.getId(), EventType.MEMBER_JOINED,
                            team.getVersion() - 1, team.getVersion(), "INVITATION_ACCEPTED",
                            command, now);
                    return success("WAITING_TEAM", team.getId(), snapshot(team, accountId));
                    }));
        } catch (DataIntegrityViolationException failure) {
            if (WaitingCreationFailureClassifier.isMembershipConflict(failure)) {
                throw new ServiceException(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS);
            }
            throw failure;
        }
        return partyResult(outcome);
    }

    public InvitationCommandResult revokeInvitation(long accountId, long teamId, long invitationId,
            IdempotencyKey key, ExpectedVersionRequest request) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        IdempotencyCommand command = command(accountId, "WAITING_PARTY_INVITATION_REVOKE", key,
                "teamId=" + teamId + "|invitationId=" + invitationId
                        + "|expectedVersion=" + request.expectedVersion());
        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    WaitingTeam team = lockTeam(teamId);
                    requireRepresentative(team, accountId);
                    team.requirePartyMutable(request.expectedVersion());
                    WaitingPartyInvitation invitation = invitationRepository
                            .findByIdForUpdate(invitationId)
                            .filter(candidate -> candidate.getWaitingTeamId() == teamId)
                            .orElseThrow(WaitingPartyService::invalidInvitation);
                    Instant now = clock.instant();
                    try { invitation.revoke(now); }
                    catch (IllegalStateException invalid) { throw invalidInvitation(); }
                    audit(team, accountId, null, EventType.INVITATION_REVOKED,
                            team.getVersion(), team.getVersion(), "INVITATION_REVOKED", command, now);
                    return success("WAITING_PARTY_INVITATION", invitation.getId(),
                            new InvitationSnapshot(
                                    Long.toString(invitation.getId()), invitation.getExpiresAt(), null));
                }));
        return new InvitationCommandResult(outcome.httpStatus(), objectMapper.treeToValue(
                outcome.data(), InvitationSnapshot.class));
    }

    public PartyCommandResult depart(long accountId, long teamId, IdempotencyKey key,
            ExpectedVersionRequest request) {
        return changeMembership(accountId, teamId, null, key, request, false);
    }

    public PartyCommandResult removeMember(long accountId, long teamId, long membershipId,
            IdempotencyKey key, ExpectedVersionRequest request) {
        return changeMembership(accountId, teamId, membershipId, key, request, true);
    }

    public TransferCommandResult proposeTransfer(long accountId, long teamId, IdempotencyKey key,
            TransferProposalRequest request) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        IdempotencyCommand command = command(accountId, "WAITING_REPRESENTATIVE_TRANSFER_PROPOSE",
                key, "teamId=" + teamId + "|targetMembershipId=" + request.targetMembershipId()
                        + "|expectedVersion=" + request.expectedVersion());
        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    WaitingTeam team = lockTeam(teamId);
                    requireRepresentative(team, accountId);
                    team.requirePartyMutable(request.expectedVersion());
                    WaitingActiveMembership target = membershipRepository
                            .findByIdAndWaitingTeamId(request.targetMembershipId(), teamId)
                            .filter(value -> !value.getConsumerAccountId()
                                    .equals(team.getConsumerAccountId()))
                            .orElseThrow(WaitingPartyService::invalidTransfer);
                    Instant now = clock.instant();
                    transferRepository.findByActiveTeamKey(teamId).ifPresent(active -> {
                        if (now.isBefore(active.getExpiresAt())) {
                            throw invalidTransfer();
                        }
                        active.expire(now);
                        transferRepository.flush();
                        audit(team, accountId, active.getTargetMembershipId(),
                                EventType.REPRESENTATIVE_TRANSFER_EXPIRED,
                                team.getVersion(), team.getVersion(), "TRANSFER_EXPIRED",
                                command, now);
                    });
                    WaitingRepresentativeTransferOffer offer = transferRepository.save(
                            WaitingRepresentativeTransferOffer.propose(
                                    teamId, accountId, target.getId(), team.getVersion(), now,
                                    now.plus(TRANSFER_TTL)));
                    audit(team, accountId, target.getId(),
                            EventType.REPRESENTATIVE_TRANSFER_PROPOSED,
                            team.getVersion(), team.getVersion(), "TRANSFER_PROPOSED", command, now);
                    return success("WAITING_REPRESENTATIVE_TRANSFER", offer.getId(),
                            transferSnapshot(offer));
                }));
        return transferResult(outcome);
    }

    public PartyCommandResult acceptTransfer(long accountId, long teamId, long offerId,
            IdempotencyKey key, ExpectedVersionRequest request) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        IdempotencyCommand command = command(accountId, "WAITING_REPRESENTATIVE_TRANSFER_ACCEPT",
                key, transferFingerprint(teamId, offerId, request.expectedVersion()));
        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    WaitingTeam team = lockTeam(teamId);
                    team.requirePartyMutable(request.expectedVersion());
                    WaitingRepresentativeTransferOffer offer = lockTransfer(teamId, offerId);
                    WaitingActiveMembership target = transferTarget(offer, teamId);
                    if (target.getConsumerAccountId() != accountId
                            || offer.getProposedTeamVersion() != request.expectedVersion()) {
                        throw invalidTransfer();
                    }
                    Instant now = clock.instant();
                    try { offer.requireAcceptable(now); }
                    catch (IllegalStateException invalid) { throw invalidTransfer(); }
                    long before = team.getVersion();
                    team.transferRepresentative(before, accountId);
                    offer.accept(now);
                    audit(team, accountId, target.getId(),
                            EventType.REPRESENTATIVE_TRANSFER_ACCEPTED,
                            before, team.getVersion(), "TRANSFER_ACCEPTED", command, now);
                    return success("WAITING_TEAM", team.getId(), snapshot(team, accountId));
                }));
        return partyResult(outcome);
    }

    public TransferCommandResult rejectTransfer(long accountId, long teamId, long offerId,
            IdempotencyKey key, ExpectedVersionRequest request) {
        return decideTransfer(accountId, teamId, offerId, key, request, false);
    }

    public TransferCommandResult revokeTransfer(long accountId, long teamId, long offerId,
            IdempotencyKey key, ExpectedVersionRequest request) {
        return decideTransfer(accountId, teamId, offerId, key, request, true);
    }

    private TransferCommandResult decideTransfer(long accountId, long teamId, long offerId,
            IdempotencyKey key, ExpectedVersionRequest request, boolean revoke) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        String type = revoke ? "WAITING_REPRESENTATIVE_TRANSFER_REVOKE"
                : "WAITING_REPRESENTATIVE_TRANSFER_REJECT";
        IdempotencyCommand command = command(accountId, type, key,
                transferFingerprint(teamId, offerId, request.expectedVersion()));
        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    WaitingTeam team = lockTeam(teamId);
                    team.requirePartyMutable(request.expectedVersion());
                    WaitingRepresentativeTransferOffer offer = lockTransfer(teamId, offerId);
                    WaitingActiveMembership target = transferTarget(offer, teamId);
                    if (revoke) requireRepresentative(team, accountId);
                    else if (target.getConsumerAccountId() != accountId) throw invalidTransfer();
                    if (offer.getProposedTeamVersion() != request.expectedVersion()) {
                        throw invalidTransfer();
                    }
                    Instant now = clock.instant();
                    try {
                        if (revoke) offer.revoke(now); else offer.reject(now);
                    } catch (IllegalStateException invalid) { throw invalidTransfer(); }
                    audit(team, accountId, target.getId(), revoke
                                    ? EventType.REPRESENTATIVE_TRANSFER_REVOKED
                                    : EventType.REPRESENTATIVE_TRANSFER_REJECTED,
                            team.getVersion(), team.getVersion(),
                            revoke ? "TRANSFER_REVOKED" : "TRANSFER_REJECTED", command, now);
                    return success("WAITING_REPRESENTATIVE_TRANSFER", offer.getId(),
                            transferSnapshot(offer));
                }));
        return transferResult(outcome);
    }

    private PartyCommandResult changeMembership(long accountId, long teamId, Long targetMembershipId,
            IdempotencyKey key, ExpectedVersionRequest request, boolean removal) {
        requireInputs(accountId, key, request);
        accountService.requireActiveAccount(accountId);
        String type = removal ? "WAITING_PARTY_MEMBER_REMOVE" : "WAITING_PARTY_MEMBER_DEPART";
        IdempotencyCommand command = command(accountId, type, key,
                "teamId=" + teamId + "|membershipId=" + targetMembershipId
                        + "|expectedVersion=" + request.expectedVersion());
        IdempotentOutcome outcome = transactionExecutor.execute(() ->
                idempotencyExecutor.execute(command, () -> {
                    WaitingTeam team = lockTeam(teamId);
                    team.requirePartyMutable(request.expectedVersion());
                    WaitingActiveMembership target;
                    if (removal) {
                        requireRepresentative(team, accountId);
                        target = membershipRepository.findByIdAndWaitingTeamId(
                                        Objects.requireNonNull(targetMembershipId), teamId)
                                .orElseThrow(WaitingPartyService::teamNotFound);
                    } else {
                        target = membershipRepository.findByConsumerAccountId(accountId)
                                .filter(candidate -> candidate.getWaitingTeamId() == teamId)
                                .orElseThrow(WaitingPartyService::teamNotFound);
                    }
                    if (target.getConsumerAccountId().equals(team.getConsumerAccountId())) {
                        throw new ServiceException(ReservationErrorCode.PARTY_MUTATION_NOT_ALLOWED);
                    }
                    long before = team.getVersion();
                    membershipRepository.delete(target);
                    team.partyChanged(before);
                    Instant now = clock.instant();
                    audit(team, accountId, target.getId(),
                            removal ? EventType.MEMBER_REMOVED : EventType.MEMBER_DEPARTED,
                            before, team.getVersion(), removal ? "MEMBER_REMOVED" : "MEMBER_DEPARTED",
                            command, now);
                    return success("WAITING_TEAM", team.getId(), snapshot(team, accountId));
                }));
        return partyResult(outcome);
    }

    private WaitingConsumerSnapshot snapshot(WaitingTeam team, long viewerAccountId) {
        long ahead = teamRepository.countActiveAhead(
                team.getStoreId(), team.getBusinessDate(), team.getQueueSequence());
        return WaitingConsumerSnapshot.from(team, ahead,
                membershipRepository.findAllByWaitingTeamIdOrderById(team.getId()), viewerAccountId);
    }

    private WaitingRepresentativeTransferOffer lockTransfer(long teamId, long offerId) {
        return transferRepository.findByIdForUpdate(offerId)
                .filter(value -> value.getWaitingTeamId() == teamId)
                .orElseThrow(WaitingPartyService::invalidTransfer);
    }

    private WaitingActiveMembership transferTarget(
            WaitingRepresentativeTransferOffer offer, long teamId) {
        return membershipRepository.findByIdAndWaitingTeamId(
                        offer.getTargetMembershipId(), teamId)
                .orElseThrow(WaitingPartyService::invalidTransfer);
    }

    private static String transferFingerprint(long teamId, long offerId, long version) {
        return "teamId=" + teamId + "|offerId=" + offerId + "|expectedVersion=" + version;
    }

    private static TransferOfferSnapshot transferSnapshot(
            WaitingRepresentativeTransferOffer offer) {
        return new TransferOfferSnapshot(Long.toString(offer.getId()),
                Long.toString(offer.getTargetMembershipId()), offer.getStatus().name(),
                offer.getProposedAt(), offer.getExpiresAt());
    }

    private TransferCommandResult transferResult(IdempotentOutcome outcome) {
        return new TransferCommandResult(outcome.httpStatus(), objectMapper.treeToValue(
                outcome.data(), TransferOfferSnapshot.class));
    }

    private void audit(WaitingTeam team, long actorId, Long subjectMembershipId,
            EventType eventType, long beforeVersion, long afterVersion, String reason,
            IdempotencyCommand command, Instant now) {
        auditRepository.save(WaitingPartyAudit.record(
                team.getId(), actorId, subjectMembershipId, eventType,
                beforeVersion, afterVersion, reason, auditCommandId(command, eventType), now));
    }

    private static String auditCommandId(IdempotencyCommand command, EventType eventType) {
        String scope = command.principalNamespace() + '|' + command.principalId() + '|'
                + command.commandType() + '|' + command.idempotencyKey() + '|' + eventType.name();
        return UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private WaitingTeam lockTeam(long teamId) {
        return teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(WaitingPartyService::teamNotFound);
    }

    private static void requireRepresentative(WaitingTeam team, long accountId) {
        if (team.getConsumerAccountId() != accountId) throw teamNotFound();
    }

    private static IdempotencyCommand command(long accountId, String type,
            IdempotencyKey key, String canonical) {
        return new IdempotencyCommand(
                "consumer", accountId, type, key.value(), RequestFingerprint.of(canonical));
    }

    private static <T> BusinessResult<T> success(String resourceType, Object resourceId, T data) {
        return new BusinessResult<>(HttpStatus.OK.value(), "SUCCESS", resourceType,
                String.valueOf(resourceId), data);
    }

    private PartyCommandResult partyResult(IdempotentOutcome outcome) {
        return new PartyCommandResult(outcome.httpStatus(), objectMapper.treeToValue(
                outcome.data(), WaitingConsumerSnapshot.class));
    }

    private static String hash(String raw) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireInputs(long accountId, IdempotencyKey key, Object request) {
        if (accountId <= 0) throw new IllegalArgumentException("accountId must be positive");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
    }

    private static ServiceException invalidInvitation() {
        return new ServiceException(ReservationErrorCode.PARTY_INVITATION_INVALID);
    }

    private static ServiceException teamNotFound() {
        return new ServiceException(ReservationErrorCode.WAITING_TEAM_NOT_FOUND);
    }

    private static ServiceException invalidTransfer() {
        return new ServiceException(ReservationErrorCode.REPRESENTATIVE_TRANSFER_INVALID);
    }
}
