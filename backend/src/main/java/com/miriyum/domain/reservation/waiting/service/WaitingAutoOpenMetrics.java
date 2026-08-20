package com.miriyum.domain.reservation.waiting.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

public class WaitingAutoOpenMetrics {

    private final MeterRegistry registry;

    public WaitingAutoOpenMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public void execution(WaitingAutoOpenService.ExecutionResult result) {
        registry.counter(
                "miriyum.waiting.auto_open.execution",
                "outcome",
                result.name().toLowerCase()).increment();
    }

    public void failure(RuntimeException failure) {
        registry.counter(
                "miriyum.waiting.auto_open.failure",
                "failure_class",
                failureClass(failure)).increment();
    }

    private static String failureClass(RuntimeException failure) {
        if (failure instanceof DataIntegrityViolationException) {
            return "integrity";
        }
        if (failure instanceof TransientDataAccessException) {
            return "transient_data";
        }
        return "unexpected";
    }
}
