package com.miriyum.domain.store.controller.publicapi;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.store.config.PublicFileSecurityConfig;
import com.miriyum.domain.store.image.PublicImageService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.storage.FileStorageObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicFileController.class)
@Import({PublicFileSecurityConfig.class, GlobalExceptionHandler.class})
class PublicFileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicImageService publicImageService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void returnsPublicImageBytesWithoutAuthentication() throws Exception {
        UUID imageId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] image = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        given(publicImageService.readPublicImage(imageId))
                .willReturn(new FileStorageObject("private-object-key", "image/png", image));

        mockMvc.perform(get("/api/v1/public-files/{imageId}", imageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(image));
    }
}
