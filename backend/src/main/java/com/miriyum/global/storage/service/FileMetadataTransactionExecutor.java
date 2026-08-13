package com.miriyum.global.storage.service;

import com.miriyum.global.storage.entity.FileMetadata;
import com.miriyum.global.storage.repository.FileMetadataRepository;
import java.sql.SQLIntegrityConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 파일 저장과 분리해 파일 메타데이터 상태를 독립 트랜잭션으로 기록한다. */
@Service
@RequiredArgsConstructor
public class FileMetadataTransactionExecutor {

    private final FileMetadataRepository fileMetadataRepository;

    /** 파일 저장을 시도하기 전에 대기 상태를 별도로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata savePending(FileMetadata metadata) {
        if (fileMetadataRepository.existsById(metadata.getFileId())) {
            throw new FileMetadataConflictException("이미 존재하는 파일 메타데이터 식별자입니다.");
        }
        try {
            return fileMetadataRepository.saveAndFlush(metadata);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateKey(exception)) {
                throw new FileMetadataConflictException("파일 메타데이터 식별자 또는 객체 키가 이미 존재합니다.", exception);
            }
            throw exception;
        }
    }

    /** 파일 저장 성공 후 대기 상태의 메타데이터를 완료 상태로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata confirm(String fileId) {
        FileMetadata metadata = findMetadata(fileId);
        metadata.confirm();
        return saveTerminalState(metadata);
    }

    /** 파일 저장 실패 후 대기 상태의 메타데이터를 실패 상태로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileMetadata fail(String fileId) {
        FileMetadata metadata = findMetadata(fileId);
        metadata.fail();
        return saveTerminalState(metadata);
    }

    private FileMetadata saveTerminalState(FileMetadata metadata) {
        try {
            return fileMetadataRepository.saveAndFlush(metadata);
        } catch (OptimisticLockingFailureException exception) {
            throw new FileMetadataConflictException("파일 메타데이터 상태가 이미 변경되었습니다.", exception);
        }
    }

    private FileMetadata findMetadata(String fileId) {
        return fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalStateException("파일 메타데이터를 찾을 수 없습니다."));
    }

    private boolean isDuplicateKey(DataIntegrityViolationException exception) {
        if (exception instanceof DuplicateKeyException) {
            return true;
        }

        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLIntegrityConstraintViolationException sqlException
                    && sqlException.getErrorCode() == 1062) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
