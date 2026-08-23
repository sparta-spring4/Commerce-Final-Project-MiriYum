package com.miriyum.domain.reservation.waiting.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository.ActiveConsumerSseHighWatermark;
import com.miriyum.domain.reservation.waiting.service.WaitingStoreAuthority;
import com.miriyum.domain.reservation.waiting.service.WaitingStoreAuthorityPort;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WaitingSseHighWatermarkSourceTest {

    @Test
    void readsEveryConsumerWatermarkInsideOneReadOnlyTransaction() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingStatusEventRepository events = mock(WaitingStatusEventRepository.class);
        WaitingStoreAuthorityPort authority = mock(WaitingStoreAuthorityPort.class);
        doAnswer(invocation -> {
            assertReadOnlyTransaction();
            return null;
        }).when(accounts).requireActiveAccount(41L);
        doAnswer(invocation -> {
            assertReadOnlyTransaction();
            return Optional.empty();
        }).when(events).findActiveConsumerSseHighWatermark(41L);
        doAnswer(invocation -> {
            assertReadOnlyTransaction();
            return 44L;
        }).when(events).findLatestOwnedConsumerSseHighWatermark(41L);
        WaitingSseHighWatermarkSource source = transactional(
                new WaitingSseHighWatermarkSource(accounts, events, authority));

        source.read(SseStreamScope.waitingConsumer(41L));
    }

    @Test
    void activeConsumerTracksOwnAccountAndCurrentStoreBusinessDate() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingStatusEventRepository events = mock(WaitingStatusEventRepository.class);
        WaitingStoreAuthorityPort authority = mock(WaitingStoreAuthorityPort.class);
        ActiveConsumerSseHighWatermark active = mock(ActiveConsumerSseHighWatermark.class);
        given(active.getWatermark()).willReturn(33L);
        given(active.getStoreId()).willReturn(103L);
        given(active.getBusinessDate()).willReturn(LocalDate.of(2026, 8, 19));
        given(events.findActiveConsumerSseHighWatermark(41L)).willReturn(Optional.of(active));
        WaitingSseHighWatermarkSource source =
                new WaitingSseHighWatermarkSource(accounts, events, authority);

        SseSignalState state = source.read(SseStreamScope.waitingConsumer(41L));

        assertThat(state.watermark()).isEqualTo(33L);
        assertThat(state.wakeUpTargets()).containsExactlyInAnyOrder(
                SseWakeUpTarget.waitingAccount(41L),
                SseWakeUpTarget.waitingStoreDate(103L, LocalDate.of(2026, 8, 19)));
        InOrder order = inOrder(accounts, events);
        order.verify(accounts).requireActiveAccount(41L);
        order.verify(events).findActiveConsumerSseHighWatermark(41L);
    }

    @Test
    void consumerWithoutMembershipTracksOnlyLatestOwnedTerminalTeam() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingStatusEventRepository events = mock(WaitingStatusEventRepository.class);
        WaitingStoreAuthorityPort authority = mock(WaitingStoreAuthorityPort.class);
        given(events.findActiveConsumerSseHighWatermark(41L)).willReturn(Optional.empty());
        given(events.findLatestOwnedConsumerSseHighWatermark(41L)).willReturn(44L);
        WaitingSseHighWatermarkSource source =
                new WaitingSseHighWatermarkSource(accounts, events, authority);

        SseSignalState state = source.read(SseStreamScope.waitingConsumer(41L));

        assertThat(state.watermark()).isEqualTo(44L);
        assertThat(state.wakeUpTargets())
                .containsExactly(SseWakeUpTarget.waitingAccount(41L));
    }

    @Test
    void storeOperatorRevalidatesReadAuthorityForEveryWatermark() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingStatusEventRepository events = mock(WaitingStatusEventRepository.class);
        WaitingStoreAuthorityPort authority = mock(WaitingStoreAuthorityPort.class);
        given(authority.requireRead(90L, 103L))
                .willReturn(new WaitingStoreAuthority(103L, ZoneId.of("Asia/Seoul")));
        given(events.findStoreSseHighWatermark(103L)).willReturn(55L);
        WaitingSseHighWatermarkSource source =
                new WaitingSseHighWatermarkSource(accounts, events, authority);

        SseSignalState state = source.read(
                SseStreamScope.waitingStoreOperator(90L, 103L));

        assertThat(source.supports(SseAudience.WAITING_STORE_OPERATOR)).isTrue();
        assertThat(state.watermark()).isEqualTo(55L);
        assertThat(state.wakeUpTargets())
                .containsExactly(SseWakeUpTarget.waitingStore(103L));
        InOrder order = inOrder(authority, events);
        order.verify(authority).requireRead(90L, 103L);
        order.verify(events).findStoreSseHighWatermark(103L);
    }

    private static WaitingSseHighWatermarkSource transactional(
            WaitingSseHighWatermarkSource target
    ) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new TestTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (WaitingSseHighWatermarkSource) factory.getProxy();
    }

    private static void assertReadOnlyTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
