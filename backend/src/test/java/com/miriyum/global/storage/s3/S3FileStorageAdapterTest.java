package com.miriyum.global.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.global.storage.FileStorageObject;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
                .build();
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(response, "image".getBytes(StandardCharsets.UTF_8)));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(5L).build());
        S3FileStorageAdapter adapter = new S3FileStorageAdapter(s3Client, "miriyum-test-bucket", 10_485_760L);

        FileStorageObject result = adapter.read("public/store/10/store-image/sample.png");

        assertThat(result.objectKey()).isEqualTo("public/store/10/store-image/sample.png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isEqualTo("image".getBytes(StandardCharsets.UTF_8));
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
