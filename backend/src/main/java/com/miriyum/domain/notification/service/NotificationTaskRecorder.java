package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;

public interface NotificationTaskRecorder {
    NotificationTaskReceipt record(NotificationSourceEventV1 event);
}
