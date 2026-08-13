package com.miriyum.global.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileStorageDeleteContractTest {

    @Test
    @DisplayName("파일 삭제는 같은 객체 키로 다시 조회할 수 없게 만든다")
    void deletesFileByObjectKey() {
        FileStoragePort storage = new InMemoryFileStorageAdapter();
        String objectKey = "public/store/10/store-image/test-file";
        storage.save(new FileStorageRequest(
                objectKey,
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))
        ));

        storage.delete(objectKey);

        assertThatThrownBy(() -> storage.read(objectKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file does not exist");
    }
}
