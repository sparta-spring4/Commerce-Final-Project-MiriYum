package com.miriyum.global.storage.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FileStorageReconciliationSchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(FileStorageReconciliationSchedulingConfig.class);

    @Test
    @DisplayName("S3 또는 reconciliation 활성화 값이 없으면 전용 스케줄러를 등록하지 않는다")
    void doesNotRegisterSchedulerWhenEitherGateIsDisabled() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean("fileStorageReconciliationTaskScheduler"));
        contextRunner.withPropertyValues("miriyum.storage.s3.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("fileStorageReconciliationTaskScheduler"));
        contextRunner.withPropertyValues("miriyum.storage.s3.reconciliation.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("fileStorageReconciliationTaskScheduler"));
    }

    @Test
    @DisplayName("S3와 reconciliation을 함께 활성화하면 전용 스케줄러와 기본 스케줄러가 모두 유지된다")
    void registersDedicatedSchedulerWithoutReplacingDefaultScheduler() {
        contextRunner.withPropertyValues(
                        "miriyum.storage.s3.enabled=true",
                        "miriyum.storage.s3.reconciliation.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("fileStorageReconciliationTaskScheduler");
                    assertThat(context).hasBean("taskScheduler");
                });
    }
}
