package com.miriyum.domain.store.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

class StoreOnboardingSubmissionBoundaryTest {

    @Test
    void submissionServiceOwnsTheDurableIdempotencyTransactionBoundary() throws Exception {
        assertThat(Arrays.stream(StoreOnboardingSubmissionService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .contains(IdempotencyExecutor.class);
        assertThat(StoreOnboardingSubmissionService.class
                .getMethod("submit", long.class, IdempotencyKey.class,
                        StoreCreateRequest.class, MultipartFile.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(StoreOnboardingSubmissionService.class
                .getMethod("supplement", long.class, long.class, IdempotencyKey.class,
                        StoreCreateRequest.class, MultipartFile.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
