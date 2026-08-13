package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuSettingResponse;

public record RepresentativeMenuCommandResult(
        int httpStatus,
        RepresentativeMenuSettingResponse data
) {
}
