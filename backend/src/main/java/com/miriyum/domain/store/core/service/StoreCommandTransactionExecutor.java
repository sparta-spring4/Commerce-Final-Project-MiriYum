package com.miriyum.domain.store.core.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Store 멱등 쓰기를 외부 지오코딩 호출과 분리된 짧은 transaction에서 실행한다.
 */
@Component
public class StoreCommandTransactionExecutor {

    private final TransactionOperations transactionOperations;

    public StoreCommandTransactionExecutor(PlatformTransactionManager transactionManager) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactionTemplate.setTimeout(5);
        this.transactionOperations = transactionTemplate;
    }

    /**
     * READ_COMMITTED·5초 제한 transaction에서 Store 쓰기 명령을 실행한다.
     *
     * @param work transaction 안에서 수행할 작업
     * @param <T> 작업 결과 타입
     * @return commit 대상 작업 결과
     */
    public <T> T execute(Supplier<T> work) {
        return transactionOperations.execute(status -> work.get());
    }
}
