package com.miriyum.domain.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationSseHighWatermarkSourceTest {

    @Test
    void readsAccountChangeVersionInsideOneReadOnlyTransaction() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        doAnswer(invocation -> {
            assertReadOnlyTransaction();
            return null;
        }).when(accounts).requireActiveAccount(41L);
        doAnswer(invocation -> {
            assertReadOnlyTransaction();
            return 113L;
        }).when(reads).findChangeVersion(41L);
        NotificationSseHighWatermarkSource source = transactional(
                new NotificationSseHighWatermarkSource(accounts, reads));

        source.read(SseStreamScope.notificationConsumer(41L));
    }

    @Test
    void returnsTheAccountChangeVersionAfterReadRoutesAreActivated() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        given(reads.findChangeVersion(41L)).willReturn(113L);
        NotificationSseHighWatermarkSource source =
                new NotificationSseHighWatermarkSource(accounts, reads);

        SseSignalState state = source.read(SseStreamScope.notificationConsumer(41L));

        assertThat(source.supports(SseAudience.NOTIFICATION_CONSUMER)).isTrue();
        assertThat(state.watermark()).isEqualTo(113L);
        assertThat(state.wakeUpTargets())
                .containsExactly(SseWakeUpTarget.notificationAccount(41L));
        verify(accounts).requireActiveAccount(41L);
        verify(reads).findChangeVersion(41L);
    }

    private static NotificationSseHighWatermarkSource transactional(
            NotificationSseHighWatermarkSource target
    ) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new TestTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (NotificationSseHighWatermarkSource) factory.getProxy();
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
