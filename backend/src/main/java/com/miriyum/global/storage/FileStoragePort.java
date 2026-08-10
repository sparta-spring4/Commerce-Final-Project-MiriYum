package com.miriyum.global.storage;

/**
 * 파일 저장 제공자와 애플리케이션 사이의 공통 경계다.
 *
 * <p>도메인은 S3 SDK나 로컬 파일 시스템을 직접 사용하지 않고 이 포트만 호출한다.
 * 실제 저장 방식은 {@link FileStoragePort} 구현체로 교체할 수 있다.</p>
 */
public interface FileStoragePort {

    /**
     * 파일을 객체 키로 저장한다.
     *
     * @param request 저장할 파일의 객체 키·형식·크기·내용
     */
    void save(FileStorageRequest request);

    /**
     * 객체 키에 해당하는 파일을 읽는다.
     *
     * @param objectKey 저장할 때 사용한 객체 키
     * @return 저장된 파일 정보와 내용
     */
    FileStorageObject read(String objectKey);

    /**
     * 저장소에서 파일을 삭제한다.
     *
     * <p>이미 존재하지 않는 파일을 삭제해도 같은 종료 상태가 되도록
     * 구현체는 멱등적으로 처리해야 한다.</p>
     */
    void delete(String objectKey);
}
