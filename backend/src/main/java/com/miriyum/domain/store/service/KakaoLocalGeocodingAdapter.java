package com.miriyum.domain.store.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.miriyum.domain.store.config.StoreGeocodingProperties;
import com.miriyum.domain.store.model.StoreGeocodingCandidate;
import com.miriyum.domain.store.model.StoreGeocodingResult;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Kakao Local 주소 검색 응답을 제공자 중립 좌표 후보로 변환한다.
 */
@Component
public class KakaoLocalGeocodingAdapter implements StoreGeocodingPort {

    private static final String PROVIDER = "KAKAO_LOCAL";
    private static final String PROVIDER_API_VERSION = "v2";
    private static final String ADDRESS_SEARCH_PATH = "/v2/local/search/address.json";

    private final RestClient restClient;
    private final StoreGeocodingProperties properties;

    public KakaoLocalGeocodingAdapter(
            @Qualifier("storeGeocodingRestClient") RestClient restClient,
            StoreGeocodingProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public StoreGeocodingResult geocode(String address) {
        if (properties.restApiKey() == null || properties.restApiKey().isBlank()) {
            throw serviceUnavailable();
        }
        KakaoAddressSearchResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(ADDRESS_SEARCH_PATH)
                            .queryParam("query", address)
                            .queryParam("analyze_type", "similar")
                            .queryParam("size", 2)
                            .build())
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "KakaoAK " + properties.restApiKey())
                    .retrieve()
                    .body(KakaoAddressSearchResponse.class);
        } catch (RestClientException exception) {
            throw serviceUnavailable();
        }
        if (response == null
                || response.meta() == null
                || response.meta().totalCount() == null
                || response.documents() == null
                || response.documents().stream().anyMatch(Objects::isNull)) {
            throw serviceUnavailable();
        }

        return new StoreGeocodingResult(
                response.meta().totalCount(),
                response.documents().stream().map(this::candidate).toList(),
                PROVIDER,
                PROVIDER_API_VERSION);
    }

    private StoreGeocodingCandidate candidate(KakaoDocument document) {
        KakaoAddress address = document.address();
        KakaoAddress roadAddress = document.roadAddress();
        String region1DepthName = address == null
                ? roadAddress == null ? null : roadAddress.region1DepthName()
                : address.region1DepthName();
        return new StoreGeocodingCandidate(
                roadAddress == null ? null : roadAddress.addressName(),
                address == null ? null : address.addressName(),
                region1DepthName,
                document.longitude(),
                document.latitude());
    }

    private static ServiceException serviceUnavailable() {
        return new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoAddressSearchResponse(
            KakaoMeta meta,
            List<KakaoDocument> documents
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoMeta(
            @JsonProperty("total_count") Integer totalCount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoDocument(
            @JsonProperty("x") String longitude,
            @JsonProperty("y") String latitude,
            KakaoAddress address,
            @JsonProperty("road_address") KakaoAddress roadAddress
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KakaoAddress(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("region_1depth_name") String region1DepthName
    ) {
    }
}
