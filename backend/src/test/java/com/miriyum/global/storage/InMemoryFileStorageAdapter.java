package com.miriyum.global.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실제 외부 저장소 없이 파일 저장 포트 계약을 검증하는 테스트용 adapter다.
 */
final class InMemoryFileStorageAdapter implements FileStoragePort {

    private final Map<String, FileStorageObject> objects = new ConcurrentHashMap<>();

    @Override
    public void save(FileStorageRequest request) {
        try (var content = request.content()) {
            byte[] bytes = content.readAllBytes();
            if (bytes.length != request.sizeBytes()) {
                throw new IllegalArgumentException("file size does not match content");
            }
            objects.put(
                    request.objectKey(),
                    new FileStorageObject(request.objectKey(), request.contentType(), bytes)
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read file content", exception);
        }
    }

    @Override
    public FileStorageObject read(String objectKey) {
        FileStorageObject stored = objects.get(objectKey);
        if (stored == null) {
            throw new IllegalArgumentException("file does not exist");
        }
        return stored;
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
    }
}
