package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.model.StoreGeocodingResult;

/**
 * 매장 주소를 외부 제공자 형식과 분리해 좌표 후보로 변환하는 경계다.
 */
public interface StoreGeocodingPort {

    /**
     * 주소에 대응하는 제공자 중립 후보를 조회한다.
     *
     * @param address 사용자가 입력한 매장 주소
     * @return 후보 수와 최소 주소·좌표 정보
     */
    StoreGeocodingResult geocode(String address);
}
