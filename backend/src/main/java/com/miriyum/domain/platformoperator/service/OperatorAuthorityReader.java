package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.platformoperator.dto.authorization.OperatorAuthority;

/** 후속 운영 도메인이 플랫폼 운영자의 현재 중앙 권한 snapshot을 읽는 공개 계약이다. */
public interface OperatorAuthorityReader {
    OperatorAuthority currentAuthority(long operatorId);

    OperatorAuthority requireCurrentAuthority(long operatorId, long expectedAuthorityVersion);
}
