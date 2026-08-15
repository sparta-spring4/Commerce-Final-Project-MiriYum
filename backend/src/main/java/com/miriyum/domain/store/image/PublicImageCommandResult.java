package com.miriyum.domain.store.image;

import com.miriyum.domain.store.dto.image.PublicImageResponse;

/** 공개 이미지 변경 명령의 HTTP 상태와 응답 본문이다. */
public record PublicImageCommandResult(int httpStatus, PublicImageResponse data) {
}
