package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.dto.storeoperator.ManagedMenuResponse;

public record MenuCommandResult(int httpStatus, ManagedMenuResponse data) {
}
