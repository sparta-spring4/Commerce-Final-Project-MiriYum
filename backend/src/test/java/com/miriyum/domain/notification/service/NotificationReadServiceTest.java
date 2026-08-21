package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.sse.SseWakeUpRequester;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationReadServiceTest {

    @Test
    void unreadCountUsesThePublicUnreadSetAsItsSourceOfTruth() {
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        given(reads.countUnread(11L)).willReturn(7L);
        NotificationReadService service = new NotificationReadService(reads, targets -> { });

        assertThat(service.getUnreadCount(11L).unreadCount()).isEqualTo(7L);
    }

    @Test
    void firstSingleReadAdvancesTheVersionAndRequestsWakeUp() {
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        RecordingWakeUps wakeUps = new RecordingWakeUps();
        given(reads.markOneRead(11L, 31L)).willReturn(NotificationReadRepository.ReadResult.CHANGED);
        given(reads.countUnread(11L)).willReturn(2L);
        NotificationReadService service = new NotificationReadService(reads, wakeUps);

        assertThat(service.readOne(11L, 31L).unreadCount()).isEqualTo(2L);

        verify(reads).advanceForRead(11L);
        assertThat(wakeUps.targets).containsExactly(
                List.of(SseWakeUpTarget.notificationAccount(11L)));
    }

    @Test
    void repeatedSingleReadIsAnIdempotentNoOp() {
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        RecordingWakeUps wakeUps = new RecordingWakeUps();
        given(reads.markOneRead(11L, 31L)).willReturn(NotificationReadRepository.ReadResult.ALREADY_READ);
        given(reads.countUnread(11L)).willReturn(2L);
        NotificationReadService service = new NotificationReadService(reads, wakeUps);

        assertThat(service.readOne(11L, 31L).unreadCount()).isEqualTo(2L);

        verify(reads).lockOrCreateChangeState(11L);
        assertThat(wakeUps.targets).isEmpty();
    }

    @Test
    void hiddenOrMissingNotificationUsesTheSameNonEnumeratingError() {
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        given(reads.markOneRead(11L, 31L)).willReturn(NotificationReadRepository.ReadResult.NOT_FOUND);
        NotificationReadService service = new NotificationReadService(reads, targets -> { });

        assertThatThrownBy(() -> service.readOne(11L, 31L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void allReadAdvancesAndWakesOnlyWhenRowsActuallyChange() {
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        RecordingWakeUps wakeUps = new RecordingWakeUps();
        given(reads.markAllRead(11L)).willReturn(3);
        given(reads.countUnread(11L)).willReturn(0L);
        NotificationReadService service = new NotificationReadService(reads, wakeUps);

        assertThat(service.readAll(11L).unreadCount()).isZero();

        verify(reads).advanceForRead(11L);
        assertThat(wakeUps.targets).hasSize(1);
    }

    private static final class RecordingWakeUps implements SseWakeUpRequester {
        private final List<List<SseWakeUpTarget>> targets = new java.util.ArrayList<>();

        @Override
        public void afterCommit(Collection<SseWakeUpTarget> targets) {
            this.targets.add(List.copyOf(targets));
        }
    }
}
