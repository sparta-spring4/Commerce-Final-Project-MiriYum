package com.miriyum.domain.auth.qrepoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ConsumerQrEpochSnapshotTest {

    private static final String OPAQUE_VERSION = "v1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void acceptsVersionedBoundedBase64UrlValue() {
        ConsumerQrEpochSnapshot snapshot = new ConsumerQrEpochSnapshot(7L, OPAQUE_VERSION);

        assertThat(snapshot.accountId()).isEqualTo(7L);
        assertThat(snapshot.opaqueVersion()).isEqualTo(OPAQUE_VERSION);
    }

    @Test
    void rejectsInvalidAccountOrOpaqueVersion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConsumerQrEpochSnapshot(0L, OPAQUE_VERSION));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConsumerQrEpochSnapshot(7L, ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConsumerQrEpochSnapshot(7L, "v2.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConsumerQrEpochSnapshot(7L, "v1.contains+padding="));
    }
}
