package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.model.StoreGeocodingCandidate;
import com.miriyum.domain.store.core.model.StoreGeocodingResult;
import com.miriyum.domain.store.core.model.VerifiedStoreGeocoding;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 외부 주소 후보를 Store가 저장할 수 있는 단일 검증 좌표로 제한한다.
 */
@Component
public class StoreGeocodingValidator {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final int COORDINATE_PRECISION = 18;
    private static final int COORDINATE_SCALE = 15;
    private static final Pattern ADDRESS_SEPARATORS =
            Pattern.compile("[\\s\\p{Z}\\-_,.()\\[\\]]+");
    private static final Pattern DETAIL_ADDRESS_TOKEN = Pattern.compile(
            "(?:지하)?[0-9a-z가-힣]+(?:층|호|동|실|관|빌딩|건물|상가)");

    /**
     * 후보 수, 주소·지역, 좌표와 최소 제공자 메타데이터를 검증한다.
     *
     * @param region 요청에서 확정한 매장 지역
     * @param requestedAddress 사용자가 입력한 매장 주소
     * @param result 제공자 중립 지오코딩 결과
     * @param verifiedAt 검증 완료 시각
     * @return 현재 주소에 저장할 검증 좌표
     * @throws ServiceException 후보가 단일·유효 주소 좌표가 아닌 경우
     */
    public VerifiedStoreGeocoding validate(
            Region region,
            String requestedAddress,
            StoreGeocodingResult result,
            Instant verifiedAt
    ) {
        requireResult(result);
        StoreGeocodingCandidate candidate = result.candidates().getFirst();
        BigDecimal latitude = coordinate(
                candidate.latitude(), MIN_LATITUDE, MAX_LATITUDE);
        BigDecimal longitude = coordinate(
                candidate.longitude(), MIN_LONGITUDE, MAX_LONGITUDE);
        String verifiedAddress = matchingAddress(requestedAddress, candidate);
        requireRegion(region, candidate.region1DepthName());
        if (verifiedAt == null) {
            throw validationFailure();
        }
        return new VerifiedStoreGeocoding(
                latitude,
                longitude,
                verifiedAddress,
                verifiedAt,
                result.provider(),
                result.providerApiVersion());
    }

    private static void requireResult(StoreGeocodingResult result) {
        if (result == null
                || result.totalCount() != 1
                || result.candidates() == null
                || result.candidates().size() != 1
                || result.candidates().getFirst() == null
                || isBlank(result.provider())
                || isBlank(result.providerApiVersion())) {
            throw validationFailure();
        }
    }

    private static BigDecimal coordinate(
            String value,
            BigDecimal minimum,
            BigDecimal maximum
    ) {
        if (isBlank(value)) {
            throw validationFailure();
        }
        try {
            BigDecimal coordinate = new BigDecimal(value);
            BigDecimal normalized = coordinate.stripTrailingZeros();
            if (coordinate.compareTo(minimum) < 0
                    || coordinate.compareTo(maximum) > 0
                    || normalized.precision() > COORDINATE_PRECISION
                    || normalized.scale() > COORDINATE_SCALE) {
                throw validationFailure();
            }
            return coordinate;
        } catch (NumberFormatException exception) {
            throw validationFailure();
        }
    }

    private static String matchingAddress(
            String requestedAddress,
            StoreGeocodingCandidate candidate
    ) {
        if (addressMatches(requestedAddress, candidate.roadAddress())) {
            return candidate.roadAddress();
        }
        if (addressMatches(requestedAddress, candidate.parcelAddress())) {
            return candidate.parcelAddress();
        }
        throw validationFailure();
    }

    private static boolean addressMatches(String requestedAddress, String providerAddress) {
        if (isBlank(requestedAddress) || isBlank(providerAddress)) {
            return false;
        }
        String requested = normalize(requestedAddress);
        String provider = normalize(providerAddress);
        if (requested.isEmpty() || provider.isEmpty()) {
            return false;
        }
        if (requested.equals(provider)) {
            return true;
        }
        String providerPrefix = provider + " ";
        if (!requested.startsWith(providerPrefix)) {
            return false;
        }
        return safeDetailSuffix(requested.substring(providerPrefix.length()));
    }

    private static boolean safeDetailSuffix(String suffix) {
        if (suffix.isBlank()) {
            return false;
        }
        for (String token : suffix.split(" ")) {
            if (!DETAIL_ADDRESS_TOKEN.matcher(token).matches()) {
                return false;
            }
        }
        return true;
    }

    private static void requireRegion(Region region, String region1DepthName) {
        if (region == null || isBlank(region1DepthName)) {
            throw validationFailure();
        }
        Set<String> expected = switch (region) {
            case SEOUL -> Set.of("서울", "서울특별시");
            case BUSAN -> Set.of("부산", "부산광역시");
            case DAEGU -> Set.of("대구", "대구광역시");
            case DAEJEON -> Set.of("대전", "대전광역시");
            case GWANGJU -> Set.of("광주", "광주광역시");
        };
        if (!expected.contains(normalize(region1DepthName))) {
            throw validationFailure();
        }
    }

    private static String normalize(String value) {
        String unicodeNormalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return ADDRESS_SEPARATORS.matcher(unicodeNormalized).replaceAll(" ").trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ServiceException validationFailure() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
