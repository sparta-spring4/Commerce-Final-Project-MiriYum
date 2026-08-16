package com.miriyum.domain.store.controller.storeoperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.dto.image.PublicImageResponse;
import com.miriyum.domain.store.image.PublicImageCommandResult;
import com.miriyum.domain.store.image.PublicImageService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreImageController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreImageControllerTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicImageService publicImageService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void authenticateStoreOperator() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
    }

    @Test
    void uploadStoreImageReturnsCreatedPublicUrl() throws Exception {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        given(publicImageService.uploadStoreImage(eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new PublicImageCommandResult(201,
                        new PublicImageResponse(imageId, "/api/v1/public-files/" + imageId)));

        MockMultipartFile image = new MockMultipartFile(
                "file", "store.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        mockMvc.perform(multipart("/api/v1/store-operators/stores/7/images")
                        .file(image)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.imageId").value(imageId.toString()))
                .andExpect(jsonPath("$.data.url").value("/api/v1/public-files/" + imageId));
    }
}
