package com.miriyum.domain.store.menu.service;

import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;

public record MenuCommandResult(int httpStatus, ManagedMenuResponse data) {
}
