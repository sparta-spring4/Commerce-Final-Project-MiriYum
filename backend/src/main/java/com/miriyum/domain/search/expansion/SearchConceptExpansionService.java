package com.miriyum.domain.search.expansion;

import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.stereotype.Service;

/** 외부 해석 실패를 빈 보완 결과로 격리하고 호출 비용을 계측한다. */
@Service
public class SearchConceptExpansionService {

    private final OpenAiSearchInterpretationProperties properties;
    private final SearchConceptInterpreter interpreter;
    private final MeterRegistry meterRegistry;

    public SearchConceptExpansionService(
            OpenAiSearchInterpretationProperties properties,
            SearchConceptInterpreter interpreter,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.interpreter = interpreter;
        this.meterRegistry = meterRegistry;
    }

    public SearchConceptExpansion expand(SearchConceptRequest request) {
        if (!properties.enabled()) {
            return SearchConceptExpansion.empty();
        }
        String purpose = request.purpose().metricValue();
        if (!ExternalSearchTextPolicy.allowsExternalInterpretation(request.text())) {
            outcome(purpose, SearchConceptFailureReason.SENSITIVE_INPUT.metricValue());
            return SearchConceptExpansion.empty();
        }
        meterRegistry.counter("miriyum.search.llm.calls", "purpose", purpose).increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SearchConceptExpansion interpreted = interpreter.interpret(request);
            if (interpreted == null) {
                outcome(purpose, SearchConceptFailureReason.MALFORMED_RESPONSE.metricValue());
                return SearchConceptExpansion.empty();
            }
            List<String> concepts = interpreted.concepts().stream()
                    .limit(properties.maxConcepts())
                    .toList();
            SearchConceptExpansion result = new SearchConceptExpansion(
                    concepts, interpreted.inputTokens(), interpreted.outputTokens());
            outcome(purpose, "success");
            tokens(purpose, "input", result.inputTokens());
            tokens(purpose, "output", result.outputTokens());
            return result;
        } catch (SearchConceptProviderException exception) {
            outcome(purpose, exception.reason().metricValue());
            tokens(purpose, "input", exception.inputTokens());
            tokens(purpose, "output", exception.outputTokens());
            return SearchConceptExpansion.empty();
        } catch (RuntimeException exception) {
            outcome(purpose, SearchConceptFailureReason.PROVIDER_ERROR.metricValue());
            return SearchConceptExpansion.empty();
        } finally {
            sample.stop(Timer.builder("miriyum.search.llm.latency")
                    .tag("purpose", purpose)
                    .register(meterRegistry));
        }
    }

    private void outcome(String purpose, String outcome) {
        meterRegistry.counter(
                "miriyum.search.llm.outcomes",
                "purpose", purpose,
                "outcome", outcome).increment();
    }

    private void tokens(String purpose, String type, long value) {
        DistributionSummary.builder("miriyum.search.llm.tokens")
                .tag("purpose", purpose)
                .tag("type", type)
                .register(meterRegistry)
                .record(value);
    }
}
