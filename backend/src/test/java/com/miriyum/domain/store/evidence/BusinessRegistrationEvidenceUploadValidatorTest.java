package com.miriyum.domain.store.evidence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.ServiceException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

class BusinessRegistrationEvidenceUploadValidatorTest {

    private final BusinessRegistrationEvidenceUploadValidator validator =
            new BusinessRegistrationEvidenceUploadValidator();

    @ParameterizedTest
    @MethodSource("validDocuments")
    void acceptsSupportedSignatures(String contentType, byte[] bytes) {
        var result = validator.validate(file(contentType, bytes));

        assertThat(result.contentType()).isEqualTo(contentType);
        assertThat(result.bytes()).containsExactly(bytes);
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void acceptsExactlyTenMebibytes() {
        byte[] bytes = Arrays.copyOf("%PDF-1.7\n%%EOF".getBytes(UTF_8), 10_485_760);

        assertThat(validator.validate(file("application/pdf", bytes)).bytes())
                .hasSize(10_485_760);
    }

    @Test
    void rejectsOneByteOverTenMebibytes() {
        byte[] bytes = Arrays.copyOf("%PDF-1.7\n%%EOF".getBytes(UTF_8), 10_485_761);

        assertThatThrownBy(() -> validator.validate(file("application/pdf", bytes)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsMimeAndSignatureMismatch() {
        assertThatThrownBy(() -> validator.validate(file(
                "image/png", "%PDF-1.7\n%%EOF".getBytes(UTF_8))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsEncryptedPdf() {
        byte[] encrypted = "%PDF-1.7\n1 0 obj<</Encrypt 2 0 R>>".getBytes(UTF_8);

        assertThatThrownBy(() -> validator.validate(file("application/pdf", encrypted)))
                .isInstanceOf(ServiceException.class);
    }

    static Stream<Arguments> validDocuments() {
        return Stream.of(
                Arguments.of("application/pdf", "%PDF-1.7\n%%EOF".getBytes(UTF_8)),
                Arguments.of("image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01}),
                Arguments.of("image/png", new byte[] {
                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
                }));
    }

    private static MockMultipartFile file(String contentType, byte[] bytes) {
        return new MockMultipartFile("businessRegistrationEvidence", "evidence", contentType, bytes);
    }
}
