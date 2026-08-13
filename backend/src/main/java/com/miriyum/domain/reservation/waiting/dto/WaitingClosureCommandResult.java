package com.miriyum.domain.reservation.waiting.dto;

public record WaitingClosureCommandResult(int httpStatus, WaitingClosureJobSnapshot data) {
}
