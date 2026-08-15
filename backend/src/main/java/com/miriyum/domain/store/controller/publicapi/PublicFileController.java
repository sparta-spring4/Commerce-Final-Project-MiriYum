package com.miriyum.domain.store.controller.publicapi;

import com.miriyum.domain.store.image.PublicImageService;
import com.miriyum.global.storage.FileStorageObject;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개로 확정된 이미지만 내부 저장소에서 읽어 반환한다. */
@RestController
@RequestMapping("/api/v1/public-files")
@RequiredArgsConstructor
public class PublicFileController {

    private final PublicImageService publicImageService;

    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> get(@PathVariable UUID imageId) {
        FileStorageObject image = publicImageService.readPublicImage(imageId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.bytes());
    }
}
