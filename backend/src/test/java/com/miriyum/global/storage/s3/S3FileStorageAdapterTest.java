package com.miriyum.global.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.global.storage.FileStorageObject;
import com.miriyum.global.storage.FileStorageOutcomeUnknownException;
import com.miriyum.global.storage.FileStorageRequest;
import com.miriyum.global.storage.FileStorageSaveResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3FileStorageAdapterTest {

    @Test
    @DisplayName("S3 어댑터는 파일을 저장하고 SHA-256 검증 정보를 반환한다")
    void savesObjectAndReturnsVerifiedMetadata() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(5L)
                        .contentType("image/jpeg")
                        .checksumSHA256("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=")
                        .build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);
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

        assertThat(putRequestCaptor.getValue().bucket()).isEqualTo("miriyum-test-bucket");
        assertThat(putRequestCaptor.getValue().key()).isEqualTo("public/store/10/menu-image/sample.jpg");
        assertThat(putRequestCaptor.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(putRequestCaptor.getValue().checksumSHA256())
                .isEqualTo("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=");
        assertThat(requestBodyCaptor.getValue().optionalContentLength()).contains(5L);
        assertThat(result.objectKey()).isEqualTo("public/store/10/menu-image/sample.jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.sizeBytes()).isEqualTo(5L);
        assertThat(result.checksum())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        verify(s3Client).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("S3 어댑터는 저장 크기와 실제 바이트 수가 다르면 거절한다")
    void rejectsSizeMismatch() {
        S3Client s3Client = mock(S3Client.class);
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);
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
    @DisplayName("S3 어댑터는 선언 크기보다 한 바이트만 더 읽어 초과 파일을 업로드 전에 거절한다")
    void rejectsOversizedContentWithoutReadingTheWholeStream() {
        S3Client s3Client = mock(S3Client.class);
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);
        FileStorageRequest request = new FileStorageRequest(
                "private/store/10/business-license/sample.jpg",
                "image/jpeg",
                5L,
                new ReadLengthLimitedInputStream("hello!".getBytes(StandardCharsets.UTF_8), 6)
        );

        assertThatThrownBy(() -> adapter.save(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file size does not match content");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("S3 어댑터는 객체 키로 파일을 다시 읽을 수 있다")
    void readsObjectByKey() {
        S3Client s3Client = mock(S3Client.class);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("image/png")
                .contentLength(5L)
                .build();
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(response, "image".getBytes(StandardCharsets.UTF_8)));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(5L).eTag("etag-before-read").build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);

        FileStorageObject result = adapter.read("public/store/10/store-image/sample.png");

        assertThat(result.objectKey()).isEqualTo("public/store/10/store-image/sample.png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isEqualTo("image".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("S3 어댑터는 HEAD에서 확인한 ETag로 GET 객체 교체를 거절한다")
    void readsObjectWithHeadEtagPrecondition() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(5L).eTag("etag-before-read").build());
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentLength(5L).contentType("image/png").build(),
                        "image".getBytes(StandardCharsets.UTF_8)));
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10L);

        adapter.read("public/store/10/store-image/sample.png");

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObjectAsBytes(requestCaptor.capture());
        assertThat(requestCaptor.getValue().ifMatch()).isEqualTo("etag-before-read");
    }

    @Test
    @DisplayName("S3 어댑터는 HEAD 뒤 더 큰 GET 응답을 최대 크기 초과로 거절한다")
    void rejectsOversizedObjectChangedAfterHead() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(5L).eTag("etag-before-read").build());
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentLength(11L).contentType("image/png").build(),
                        "larger-image".getBytes(StandardCharsets.UTF_8)));
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10L);

        assertThatThrownBy(() -> adapter.read("public/store/10/store-image/sample.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured limit");
    }

    @Test
    @DisplayName("S3 어댑터는 저장 결과 검증이 실패하면 업로드한 객체를 보상 삭제한다")
    void deletesUploadedObjectWhenStoredMetadataVerificationFails() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("uploaded-version").build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(6L)
                        .contentType("image/jpeg")
                        .checksumSHA256("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=")
                        .build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);
        FileStorageRequest request = new FileStorageRequest(
                "public/store/10/menu-image/sample.jpg",
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> adapter.save(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stored file size");

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("miriyum-test-bucket");
        assertThat(deleteCaptor.getValue().key()).isEqualTo("public/store/10/menu-image/sample.jpg");
        assertThat(deleteCaptor.getValue().versionId()).isEqualTo("uploaded-version");
    }

    @Test
    @DisplayName("S3 어댑터는 업로드 버전을 식별할 수 없으면 다른 요청의 객체를 지우지 않는다")
    void doesNotDeleteByKeyWhenUploadVersionIsUnavailable() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(6L)
                        .contentType("image/jpeg")
                        .checksumSHA256("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=")
                        .build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);
        FileStorageRequest request = new FileStorageRequest(
                "public/store/10/menu-image/sample.jpg",
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> adapter.save(request))
                .isInstanceOf(FileStorageOutcomeUnknownException.class);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("S3 어댑터는 업로드 version ID를 알 수 없으면 다른 요청의 객체를 삭제하지 않는다")
    void doesNotDeleteByKeyWhenUploadVersionIsUnavailable() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("null").build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(6L)
                        .contentType("image/jpeg")
                        .checksumSHA256("LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=")
                        .build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);
        FileStorageRequest request = new FileStorageRequest(
                "public/store/10/menu-image/sample.jpg",
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> adapter.save(request))
                .isInstanceOf(FileStorageOutcomeUnknownException.class);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("S3 어댑터는 PutObject 응답 유실을 결과 불명으로 보존한다")
    void keepsOutcomeUnknownWhenPutObjectResponseIsLost() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("S3 response timed out"));
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);

        assertThatThrownBy(() -> adapter.save(request("public/store/10/menu-image/sample.jpg")))
                .isInstanceOf(FileStorageOutcomeUnknownException.class)
                .hasMessageContaining("PutObject outcome");

        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("S3 어댑터는 Versioning 활성 버킷에서 저장을 거절한다")
    void rejectsSaveWhenBucketVersioningIsEnabled() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);

        assertThatThrownBy(() -> adapter.save(request("public/store/10/menu-image/sample.jpg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Versioning must be disabled");

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("S3 어댑터는 Versioning 활성 버킷에서 삭제를 거절한다")
    void rejectsDeleteWhenBucketVersioningIsEnabled() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.SUSPENDED)
                        .build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);

        assertThatThrownBy(() -> adapter.delete("public/store/10/menu-image/sample.jpg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Versioning must be disabled");

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("S3 어댑터는 객체 키로 삭제를 요청한다")
    void deletesObjectByKey() {
        S3Client s3Client = mock(S3Client.class);
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);

        adapter.delete("public/store/10/store-image/sample.png");

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("miriyum-test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("public/store/10/store-image/sample.png");
    }

    private FileStorageRequest request(String objectKey) {
        return new FileStorageRequest(
                objectKey,
                "image/jpeg",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))
        );
    }

    private static final class ReadLengthLimitedInputStream extends InputStream {

        private final byte[] bytes;
        private final int maximumReadLength;
        private int position;

        private ReadLengthLimitedInputStream(byte[] bytes, int maximumReadLength) {
            this.bytes = bytes;
            this.maximumReadLength = maximumReadLength;
        }

        @Override
        public int read() {
            if (position >= bytes.length) {
                return -1;
            }
            return bytes[position++];
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length > maximumReadLength) {
                throw new IOException("unbounded read requested");
            }
            if (position >= bytes.length) {
                return -1;
            }
            int copied = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, target, offset, copied);
            position += copied;
            return copied;
        }
    }
}
