package com.miriyum.domain.auth.qrepoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.Test;

class ConsumerQrEpochPropertiesTest {

    @Test
    void returnsConfiguredStorageGeneration() {
        ConsumerQrEpochProperties properties = new ConsumerQrEpochProperties();
        properties.setStorageGeneration("restore-2026_08.14");

        assertThat(properties.requireStorageGeneration()).isEqualTo("restore-2026_08.14");
    }

    @Test
    void rejectsMissingOrInvalidStorageGeneration() {
        for (String invalid : new String[]{null, "", " ", "contains/slash", "한글", "a".repeat(65)}) {
            ConsumerQrEpochProperties properties = new ConsumerQrEpochProperties();
            properties.setStorageGeneration(invalid);

            assertThatThrownBy(properties::requireStorageGeneration)
                    .isInstanceOf(ServiceException.class)
                    .extracting(exception -> ((ServiceException) exception).getErrorCode())
                    .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
