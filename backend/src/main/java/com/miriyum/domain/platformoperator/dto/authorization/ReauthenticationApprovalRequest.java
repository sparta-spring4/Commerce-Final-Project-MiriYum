package com.miriyum.domain.platformoperator.dto.authorization;

import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReauthenticationApprovalRequest(
        @NotBlank @Size(min = 8, max = 64) String currentPassword,
        @NotNull AdminCommandPurpose purpose,
        @NotNull AdminTargetType targetType,
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$") String targetId
) {
    @Override
    public String toString() {
        return "ReauthenticationApprovalRequest[currentPassword=<redacted>, purpose=" + purpose
                + ", targetType=" + targetType + ", targetId=" + targetId + "]";
    }
}
