package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.sse.SseWakeUpRequester;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 소비자 알림의 미확인 집계와 명시적인 읽음 변경을 소유한다. */
@Service
public class NotificationReadService {

    private final NotificationReadRepository readRepository;
    private final SseWakeUpRequester wakeUpRequester;

    public NotificationReadService(
            NotificationReadRepository readRepository,
            SseWakeUpRequester wakeUpRequester
    ) {
        this.readRepository = readRepository;
        this.wakeUpRequester = wakeUpRequester;
    }

    /** 본인 공개 전달 완료 알림 중 아직 읽지 않은 전체 개수를 반환한다. */
    @Transactional(readOnly = true)
    public NotificationUnreadCountResponse getUnreadCount(long consumerAccountId) {
        try {
            return response(consumerAccountId);
        } catch (DataAccessException unavailable) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 본인 공개 알림 하나를 최초 한 번만 읽음 처리한다. */
    @Transactional
    public NotificationUnreadCountResponse readOne(long consumerAccountId, long notificationId) {
        try {
            readRepository.lockOrCreateChangeState(consumerAccountId);
            NotificationReadRepository.ReadResult result =
                    readRepository.markOneRead(consumerAccountId, notificationId);
            if (result == NotificationReadRepository.ReadResult.NOT_FOUND) {
                throw new ServiceException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
            }
            if (result == NotificationReadRepository.ReadResult.CHANGED) {
                readRepository.advanceForRead(consumerAccountId);
                requestWakeUp(consumerAccountId);
            }
            return response(consumerAccountId);
        } catch (DataAccessException unavailable) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 직렬화 시점까지 본인에게 공개된 모든 미확인 알림을 읽음 처리한다. */
    @Transactional
    public NotificationUnreadCountResponse readAll(long consumerAccountId) {
        try {
            readRepository.lockOrCreateChangeState(consumerAccountId);
            if (readRepository.markAllRead(consumerAccountId) > 0) {
                readRepository.advanceForRead(consumerAccountId);
                requestWakeUp(consumerAccountId);
            }
            return response(consumerAccountId);
        } catch (DataAccessException unavailable) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private NotificationUnreadCountResponse response(long consumerAccountId) {
        return new NotificationUnreadCountResponse(readRepository.countUnread(consumerAccountId));
    }

    private void requestWakeUp(long consumerAccountId) {
        wakeUpRequester.afterCommit(List.of(SseWakeUpTarget.notificationAccount(consumerAccountId)));
    }
}
