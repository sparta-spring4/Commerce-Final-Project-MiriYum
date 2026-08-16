package com.miriyum.domain.analytics.service;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

/** 알려진 source 운영 실패만 공개 가능한 metric reason으로 축약한다. */
@Component
public class AnalyticsMetricFailureClassifier {

    public MetricReasonCode classify(RuntimeException failure) {
        if (failure instanceof QueryTimeoutException) {
            return MetricReasonCode.SOURCE_DELAYED;
        }
        if (failure instanceof DataIntegrityViolationException) {
            return MetricReasonCode.SOURCE_QUARANTINED;
        }
        if (failure instanceof DataAccessResourceFailureException) {
            return MetricReasonCode.SOURCE_FAILED;
        }
        throw failure;
    }
}
