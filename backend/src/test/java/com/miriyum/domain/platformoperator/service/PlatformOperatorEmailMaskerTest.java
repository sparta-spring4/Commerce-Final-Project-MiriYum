package com.miriyum.domain.platformoperator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformOperatorEmailMaskerTest {

    @Test
    void masksLocalPartAndDomainWithoutExposingTheOriginalAddress() {
        assertThat(PlatformOperatorEmailMasker.mask("alice@example.com"))
                .isEqualTo("al***@e******.com");
        assertThat(PlatformOperatorEmailMasker.mask("ab@example.com"))
                .isEqualTo("a*@e******.com");
    }

    @Test
    void malformedStoredAddressFailsClosed() {
        assertThat(PlatformOperatorEmailMasker.mask("malformed"))
                .isEqualTo("***");
    }
}
