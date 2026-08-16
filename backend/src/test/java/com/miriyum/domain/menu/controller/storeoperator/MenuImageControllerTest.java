package com.miriyum.domain.menu.controller.storeoperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.menu.image.MenuImageCommandResult;
import com.miriyum.domain.menu.image.MenuImageService;
import com.miriyum.domain.menu.image.MenuPublicImageResponse;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MenuImageController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class MenuImageControllerTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MenuImageService menuImageService;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void authenticateStoreOperator() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
    }

    @Test
    void putMenuImageReturnsPublicUrlWithoutInternalImageId() throws Exception {
        given(menuImageService.putMenuImage(eq(11L), eq(7L), eq(13L), any(IdempotencyKey.class), any()))
                .willReturn(new MenuImageCommandResult(200,
                        new MenuPublicImageResponse("/api/v1/public-files/123e4567-e89b-12d3-a456-426614174000")));
        MockMultipartFile image = new MockMultipartFile("file", "menu.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        mockMvc.perform(multipart("/api/v1/store-operators/stores/7/menus/13/images")
                        .file(image)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.url").value("/api/v1/public-files/123e4567-e89b-12d3-a456-426614174000"))
                .andExpect(jsonPath("$.data.imageId").doesNotExist());
    }

    @Test
    void deleteMenuImageWithoutExistingImageReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/store-operators/stores/7/menus/13/images")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isNoContent());
    }
}
