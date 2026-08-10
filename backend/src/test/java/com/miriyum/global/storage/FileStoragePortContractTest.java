package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStoragePortContractTest {

    @Test
    @DisplayName("파일 저장소는 저장한 파일을 같은 객체 키로 다시 읽을 수 있다")
    void savesAndReadsFileByObjectKey() {
        FileStoragePort storage = new InMemoryFileStorageAdapter();
        FileStorageRequest request = new FileStorageRequest(
                "private/store/10/business-license/test-file",
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))
        );

        storage.save(request);

        FileStorageObject stored = storage.read(request.objectKey());

        assertThat(stored.objectKey()).isEqualTo(request.objectKey());
        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.bytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }
}
