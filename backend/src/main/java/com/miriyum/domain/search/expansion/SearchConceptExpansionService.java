package com.miriyum.domain.search.expansion;

import com.miriyum.domain.search.config.OpenAiSearchInterpretationProperties;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.expansion.StructuredFoodEvidence.EvidenceTerm;
import com.miriyum.domain.search.interpreter.FoodEvidenceVocabulary;
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
    private final FoodEvidenceVocabulary vocabulary;
    private final MeterRegistry meterRegistry;

    public SearchConceptExpansionService(
            OpenAiSearchInterpretationProperties properties,
            SearchConceptInterpreter interpreter,
            FoodEvidenceVocabulary vocabulary,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.interpreter = interpreter;
        this.vocabulary = vocabulary;
        this.meterRegistry = meterRegistry;
    }

    public SearchConceptExpansion expand(SearchConceptRequest request) {
        return expand(request, StructuredFoodEvidence.empty());
    }

    public SearchConceptExpansion expand(
            SearchConceptRequest request,
            StructuredFoodEvidence deterministicEvidence
    ) {
        if (deterministicEvidence == null) {
            throw new IllegalArgumentException("deterministicEvidence must not be null");
        }
        if (!properties.enabled()) {
            return deterministicOnly(deterministicEvidence);
        }
        String purpose = request.purpose().metricValue();
        if (!ExternalSearchTextPolicy.allowsExternalInterpretation(request, vocabulary)) {
            outcome(purpose, SearchConceptFailureReason.SENSITIVE_INPUT.metricValue());
            return deterministicOnly(deterministicEvidence);
        }
        meterRegistry.counter("miriyum.search.llm.calls", "purpose", purpose).increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SearchConceptExpansion interpreted = interpreter.interpret(request);
            if (interpreted == null) {
                outcome(purpose, SearchConceptFailureReason.MALFORMED_RESPONSE.metricValue());
                return deterministicOnly(deterministicEvidence);
            }
            List<String> concepts = interpreted.concepts().stream()
                    .limit(properties.maxConcepts())
                    .toList();
            SearchConceptExpansion result = new SearchConceptExpansion(
                    concepts,
                    deterministicEvidence.fillEmptyDimensionsFrom(
                            validateLlmEvidence(interpreted.foodEvidence())),
                    interpreted.inputTokens(),
                    interpreted.outputTokens());
            outcome(purpose, "success");
            tokens(purpose, "input", result.inputTokens());
            tokens(purpose, "output", result.outputTokens());
            return result;
        } catch (SearchConceptProviderException exception) {
            outcome(purpose, exception.reason().metricValue());
            tokens(purpose, "input", exception.inputTokens());
            tokens(purpose, "output", exception.outputTokens());
            return deterministicOnly(deterministicEvidence);
        } catch (RuntimeException exception) {
            outcome(purpose, SearchConceptFailureReason.PROVIDER_ERROR.metricValue());
            return deterministicOnly(deterministicEvidence);
        } finally {
            sample.stop(Timer.builder("miriyum.search.llm.latency")
                    .tag("purpose", purpose)
                    .register(meterRegistry));
        }
    }

    private StructuredFoodEvidence validateLlmEvidence(StructuredFoodEvidence evidence) {
        if (evidence == null) {
            return StructuredFoodEvidence.empty();
        }
        return new StructuredFoodEvidence(
                evidence.rawFoodSpans().stream()
                        .filter(term -> term.source() == StructuredFoodEvidenceSource.LLM)
                        .toList(),
                resolve(Dimension.MENU_FAMILY, evidence.menuFamilies()),
                resolve(Dimension.INGREDIENT, evidence.ingredients()),
                resolve(Dimension.TASTE, evidence.tastes()),
                resolve(Dimension.BROTH, evidence.broths()),
                resolve(Dimension.METHOD, evidence.methods()),
                resolve(Dimension.AROMA, evidence.aromas()),
                resolve(Dimension.TEXTURE, evidence.textures()),
                resolve(Dimension.FORM, evidence.forms()));
    }

    private List<EvidenceTerm> resolve(
            Dimension dimension,
            List<EvidenceTerm> values
    ) {
        return values.stream()
                .map(EvidenceTerm::surface)
                .map(value -> vocabulary.resolve(
                        dimension, value, StructuredFoodEvidenceSource.LLM))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static SearchConceptExpansion deterministicOnly(
            StructuredFoodEvidence evidence
    ) {
        return new SearchConceptExpansion(List.of(), evidence, 0, 0);
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
