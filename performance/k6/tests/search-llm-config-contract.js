import { check } from 'k6'

import { loadSearchLlmConfig } from '../search-llm/config.js'

export const options = { thresholds: { checks: ['rate==1'] } }

function throws(action) { try { action(); return false } catch (_) { return true } }

const VALID = {
  TARGET_ENV: 'staging', BASE_URL: 'https://staging-api.miriyum.click',
  ALLOWED_HOSTS: 'staging-api.miriyum.click', STAGING_APPROVED: 'true',
  STAGING_HARNESS_SOURCE_VERIFIED: 'true', LLM_LIVE_TEST_APPROVED: 'true',
  COMMIT_SHA: '0123456789abcdef0123456789abcdef01234567',
  HARNESS_COMMIT_SHA: '0123456789abcdef0123456789abcdef01234567',
  LLM_RUN_ID: 'staging-llm-01', LLM_FIXTURE_PATH: '/scripts/fixtures/search-llm.local.json',
  LLM_BUDGET_PROOF_PATH: '/scripts/fixtures/search-llm-budget.local.json',
  LLM_SCENARIOS: 'exact,natural-language,same-store,nearby-store',
  LLM_DURATION_SECONDS: '30',
  LLM_PLANNED_CALLS: '150', LLM_CUMULATIVE_CALLS: '20',
  LLM_PLANNED_COST_USD: '0.30', LLM_CUMULATIVE_COST_USD: '0.10',
}

export default function () {
  check(null, {
    'approved bounded staging config is accepted': () => loadSearchLlmConfig(VALID).limits.durationSeconds === 30,
    'live approval is mandatory': () => throws(() => loadSearchLlmConfig({ ...VALID, LLM_LIVE_TEST_APPROVED: 'false' })),
    'unused VU and arrival inputs are not part of the execution contract': () => {
      const config = loadSearchLlmConfig({ ...VALID, LLM_MAX_VUS: '99', LLM_ARRIVAL_RATE: '99' })
      return config.limits.maxVus === undefined && config.limits.arrivalRate === undefined
    },
    'production target is rejected': () => throws(() => loadSearchLlmConfig({ ...VALID, BASE_URL: 'https://api.miriyum.com', ALLOWED_HOSTS: 'api.miriyum.com' })),
    'fallback scenario requires its controlled runtime mode': () => throws(() => loadSearchLlmConfig({
      ...VALID, LLM_SCENARIOS: 'fallback',
    })),
    'fallback scenario is accepted as an isolated controlled run': () => loadSearchLlmConfig({
      ...VALID, LLM_SCENARIOS: 'fallback', LLM_FALLBACK_MODE: 'disabled',
    }).fallbackMode === 'disabled',
    'fallback scenario cannot be mixed with another runtime contract': () => throws(() => loadSearchLlmConfig({
      ...VALID, LLM_SCENARIOS: 'natural-language,fallback', LLM_FALLBACK_MODE: 'disabled',
    })),
  })
}
