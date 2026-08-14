package com.miriyum.global.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3FileStorageAdapterTest {

    @Test
    @DisplayName("S3 어댑터는 파일을 저장하고 SHA-256 검증 정보를 반환한다")
    void savesObjectAndReturnsVerifiedMetadata() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket");
        FileStorageRequest request = new FileStorageRequest(
                "public/store/10/menu-image/sample.jpg",
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))
        );

        FileStorageSaveResult result = adapter.save(request);

        ArgumentCaptor<PutObjectRequest> putRequestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> requestBodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(putRequestCaptor.capture(), requestBodyCaptor.capture());
        byte[] uploadedBytes = requestBodyCaptor.getValue()
                .contentStreamProvider()
                .newStream()
                .readAllBytes();

        assertThat(putRequestCaptor.getValue().bucket()).isEqualTo("miriyum-test-bucket");
        assertThat(putRequestCaptor.getValue().key()).isEqualTo("public/store/10/menu-image/sample.jpg");
        assertThat(putRequestCaptor.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(uploadedBytes).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(result.objectKey()).isEqualTo("public/store/10/menu-image/sample.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.sizeBytes()).isEqualTo(5L);
        assertThat(result.checksum())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("S3 어댑터는 저장 크기와 실제 바이트 수가 다르면 거절한다")
    void rejectsSizeMismatch() {
        S3Client s3Client = mock(S3Client.class);
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket");
        FileStorageRequest request = new FileStorageRequest(
                "private/store/10/business-license/sample.jpg",
                "image/jpeg",
                7L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))
        );

        assertThatThrownBy(() -> adapter.save(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file size does not match content");
    }

    @Test
    @DisplayName("S3 어댑터는 객체 키로 파일을 다시 읽을 수 있다")
    void readsObjectByKey() {
        S3Client s3Client = mock(S3Client.class);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("image/png")
                .build();
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(response, "image".getBytes(StandardCharsets.UTF_8)));
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket");

        FileStorageObject result = adapter.read("public/store/10/store-image/sample.png");

        assertThat(result.objectKey()).isEqualTo("public/store/10/store-image/sample.png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isEqualTo("image".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("S3 어댑터는 객체 키로 삭제를 요청한다")
    void deletesObjectByKey() {
        S3Client s3Client = mock(S3Client.class);
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket");

        adapter.delete("public/store/10/store-image/sample.png");

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("miriyum-test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("public/store/10/store-image/sample.png");
    }
}
