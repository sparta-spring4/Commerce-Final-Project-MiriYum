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
  LLM_SCENARIOS: 'exact,natural-language,same-store,nearby-store,fallback',
  LLM_FALLBACK_MODE: 'disabled',
  LLM_MAX_VUS: '2', LLM_ARRIVAL_RATE: '1', LLM_DURATION_SECONDS: '30',
  LLM_PLANNED_CALLS: '150', LLM_CUMULATIVE_CALLS: '20',
  LLM_PLANNED_COST_USD: '0.30', LLM_CUMULATIVE_COST_USD: '0.10',
}

export default function () {
  check(null, {
    'approved bounded staging config is accepted': () => loadSearchLlmConfig(VALID).limits.maxVus === 2,
    'live approval is mandatory': () => throws(() => loadSearchLlmConfig({ ...VALID, LLM_LIVE_TEST_APPROVED: 'false' })),
    'VU ceiling is two': () => throws(() => loadSearchLlmConfig({ ...VALID, LLM_MAX_VUS: '3' })),
    'arrival ceiling is one': () => throws(() => loadSearchLlmConfig({ ...VALID, LLM_ARRIVAL_RATE: '2' })),
    'production target is rejected': () => throws(() => loadSearchLlmConfig({ ...VALID, BASE_URL: 'https://api.miriyum.com', ALLOWED_HOSTS: 'api.miriyum.com' })),
    'fallback scenario requires its controlled runtime mode': () => {
      const env = { ...VALID }; delete env.LLM_FALLBACK_MODE
      return throws(() => loadSearchLlmConfig(env))
    },
  })
}
