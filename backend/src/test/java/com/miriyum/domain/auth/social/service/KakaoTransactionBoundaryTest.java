package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.consumer.service.ConsumerKakaoAuthService;
import com.miriyum.domain.consumer.service.ConsumerKakaoLinkTransactionService;
import com.miriyum.domain.storeoperator.service.StoreOperatorKakaoAuthService;
import com.miriyum.domain.storeoperator.service.StoreOperatorKakaoLinkTransactionService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class KakaoTransactionBoundaryTest {

    @Test
    @DisplayName("카카오 연결과 가입의 외부 트랜잭션은 READ_COMMITTED와 5초 제한을 사용한다")
    void usesReadCommittedAtPublicTransactionBoundaries() throws NoSuchMethodException {
        assertTransactionBoundary(ConsumerKakaoLinkTransactionService.class, "linkActiveAccount", Long.class, String.class);
        assertTransactionBoundary(StoreOperatorKakaoLinkTransactionService.class, "linkActiveAccount", Long.class, String.class);
        assertTransactionBoundary(ConsumerKakaoAuthService.class, "signUp",
                com.miriyum.domain.consumer.dto.auth.ConsumerKakaoSignUpRequest.class);
        assertTransactionBoundary(StoreOperatorKakaoAuthService.class, "signUp",
                com.miriyum.domain.storeoperator.dto.auth.StoreOperatorKakaoSignUpRequest.class);
    }

    private void assertTransactionBoundary(Class<?> type, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(transactional.timeout()).isEqualTo(5);
    }
}
