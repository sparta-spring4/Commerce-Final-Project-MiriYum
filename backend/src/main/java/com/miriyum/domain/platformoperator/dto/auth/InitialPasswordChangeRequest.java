package com.miriyum.domain.platformoperator.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record InitialPasswordChangeRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword,
        @NotBlank String newPasswordConfirm) {
}
