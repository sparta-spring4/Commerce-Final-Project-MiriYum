package com.miriyum.domain.store.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicImageUploadValidatorTest {

    private final PublicImageUploadValidator validator = new PublicImageUploadValidator(10 * 1024 * 1024);

    @Test
    @DisplayName("PNG MIME 타입과 PNG 시그니처가 일치하면 공개 이미지 업로드 정보를 만든다")
    void validatesPngContent() {
        byte[] content = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01};

        ValidatedPublicImage image = validator.validate("image/png", content);

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.extension()).isEqualTo("png");
        assertThat(image.bytes()).isEqualTo(content);
    }

    @Test
    @DisplayName("요청 MIME 타입과 파일 시그니처가 다르면 공개 이미지 업로드를 거절한다")
    void rejectsMismatchedContentTypeAndSignature() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        assertThatIllegalArgumentException()
                .isThrownBy(() -> validator.validate("image/jpeg", png));
    }

    @Test
    @DisplayName("크기 제한을 넘는 공개 이미지 업로드를 거절한다")
    void rejectsContentLargerThanConfiguredLimit() {
        PublicImageUploadValidator smallLimitValidator = new PublicImageUploadValidator(8);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01};

        assertThatIllegalArgumentException()
                .isThrownBy(() -> smallLimitValidator.validate("image/png", png));
    }
}
