package com.miriyum.domain.reservation.waiting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/** 소비자 일행 참여와 구성 변경의 공개 계약이다. */
public final class WaitingPartyContracts {

    private WaitingPartyContracts() { }

    public enum MemberRole { REPRESENTATIVE, MEMBER }

    public record ExpectedVersionRequest(
            @NotNull @PositiveOrZero Long expectedVersion
    ) { }

    public record InvitationAcceptanceRequest(@NotBlank String invitationCode) {
        public InvitationAcceptanceRequest {
            if (invitationCode == null || invitationCode.isBlank()
                    || invitationCode.length() > 128) {
                throw new IllegalArgumentException("invitationCode must contain 1 to 128 characters");
            }
            invitationCode = invitationCode.trim();
        }
    }

    public record TransferProposalRequest(
            @NotNull @jakarta.validation.constraints.Positive Long targetMembershipId,
            @NotNull @PositiveOrZero Long expectedVersion
    ) { }

    public record TransferOfferSnapshot(
            String offerId,
            String targetMembershipId,
            String status,
            Instant proposedAt,
            Instant expiresAt
    ) { }

    public record MemberSnapshot(
            String membershipId,
            MemberRole role,
            Instant joinedAt,
            boolean self
    ) { }

    public record InvitationSnapshot(
            String invitationId,
            Instant expiresAt,
            String invitationCode
    ) {
        public InvitationSnapshot withFreshCode(String rawCode) {
            return new InvitationSnapshot(invitationId, expiresAt, rawCode);
        }
    }

    public record InvitationCommandResult(int httpStatus, InvitationSnapshot data) { }
    public record TransferCommandResult(int httpStatus, TransferOfferSnapshot data) { }
    public record PartyCommandResult(int httpStatus, WaitingConsumerSnapshot data) { }
}
