package com.miriyum.domain.platformoperator.dto.management;

import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import jakarta.validation.constraints.NotNull;

public record PlatformOperatorSuspensionRequest(@NotNull PlatformOperatorAuditReason reason) {
}
