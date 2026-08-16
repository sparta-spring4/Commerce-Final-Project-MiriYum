package com.miriyum.domain.search.semantic;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.menu.service.MenuCommandService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MenuSemanticIndexListenerTest {

    @Mock SemanticMenuIndexer indexer;

    @Test
    void retriesTransientIndexFailureUpToSuccess() {
        doThrow(new IllegalStateException("first"))
                .doThrow(new IllegalStateException("second"))
                .doNothing()
                .when(indexer).reindex(11L);

        new MenuSemanticIndexListener(indexer).reindex(
                new MenuCommandService.SemanticIndexChanged(11L));

        verify(indexer, times(3)).reindex(11L);
    }

    @Test
    void recordsIndexLagAfterSuccessfulReindex() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MenuSemanticIndexListener(indexer, registry).reindex(
                new MenuCommandService.SemanticIndexChanged(11L));

        org.assertj.core.api.Assertions.assertThat(registry.timer(
                        "miriyum.search.semantic.index.lag").count())
                .isEqualTo(1L);
    }

    @Test
    void dispatchesOnlyAfterCommitAndSuppressesRolledBackSignal() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TransactionEventTestConfiguration.class);
            context.registerBean(SemanticMenuIndexer.class, () -> indexer);
            context.registerBean(io.micrometer.core.instrument.MeterRegistry.class,
                    SimpleMeterRegistry::new);
            context.registerBean(MenuSemanticIndexListener.class);
            context.refresh();
            TransactionTemplate transaction = new TransactionTemplate(
                    new TestTransactionManager());

            transaction.executeWithoutResult(status -> context.publishEvent(
                    new MenuCommandService.SemanticIndexChanged(11L)));

            verify(indexer).reindex(11L);
            clearInvocations(indexer);

            transaction.executeWithoutResult(status -> {
                context.publishEvent(new MenuCommandService.SemanticIndexChanged(12L));
                status.setRollbackOnly();
            });

            verifyNoInteractions(indexer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionEventTestConfiguration {
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
