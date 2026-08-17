package com.miriyum.domain.menu.image;

/** 메뉴 이미지 변경의 멱등 재생 결과를 HTTP 응답으로 전달한다. */
public record MenuImageCommandResult(int httpStatus, MenuPublicImageResponse data) {
}
