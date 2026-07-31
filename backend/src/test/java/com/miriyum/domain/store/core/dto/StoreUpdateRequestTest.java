package com.miriyum.domain.store.core.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.core.enums.OperationStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreUpdateRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("수정 필드가 하나도 없으면 validation으로 거부한다")
    void rejectsRequestWithoutAnyField() {
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, null, null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("anyFieldPresent");
    }

    @Test
    @DisplayName("빈 설명은 설명을 지우는 명시적 수정 요청으로 인정한다")
    void acceptsExplicitEmptyDescription() {
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, "", null, null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("일반 매장 수정 요청은 CLOSED를 허용하지 않는다")
    void rejectsClosedOperationStatus() {
        StoreUpdateRequest request = new StoreUpdateRequest(
                null, null, null, null, null, null, null,
                OperationStatus.CLOSED);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nonTerminalOperationStatus");
    }
}
