package com.miriyum.global.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageMetadataTest {

    @Test
    @DisplayName("페이지 메타데이터는 0 기반 페이지와 빈 결과 정보를 표현한다")
    void exposesApprovedZeroBasedPageFields() {
        PageMetadata page = new PageMetadata(0, 20, 0, 0, false);

        assertThat(page.number()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.hasNext()).isFalse();
    }
}
