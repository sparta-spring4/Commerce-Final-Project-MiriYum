package com.miriyum.global.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStorageDeleteIdempotencyContractTest {

    @Test
    @DisplayName("존재하지 않는 파일을 삭제해도 성공한다")
    void deletingMissingFileIsIdempotent() {
        FileStoragePort storage = new InMemoryFileStorageAdapter();
        String objectKey = "public/store/10/store-image/missing-file";

        assertDoesNotThrow(() -> storage.delete(objectKey));
        assertDoesNotThrow(() -> storage.delete(objectKey));
    }
}
