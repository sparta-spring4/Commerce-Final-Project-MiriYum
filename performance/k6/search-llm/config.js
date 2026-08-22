import { validateShaEvidence } from '../config.js'
import { assertSafeTarget, parsePositiveInt } from '../lib/safety.js'
import { validateBudget } from './budget.js'

export const SEARCH_LLM_SCENARIOS = Object.freeze([
  'exact', 'natural-language', 'same-store', 'nearby-store', 'fallback',
])
const SCENARIOS = new Set(SEARCH_LLM_SCENARIOS)

function text(name, value) {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${name} is required`)
  return value.trim()
}
function decimal(name, value) {
  const parsed = Number(text(name, value))
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error(`${name} must be non-negative`)
  return parsed
}
function integer(name, value) {
  const parsed = decimal(name, value)
  if (!Number.isInteger(parsed)) throw new Error(`${name} must be an integer`)
  return parsed
}
function runId(value) {
  const result = text('LLM_RUN_ID', value)
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(result)) throw new Error('LLM_RUN_ID is invalid')
  return result
}
function jsonPath(name, value) {
  const result = text(name, value)
  if (!result.endsWith('.json')) throw new Error(`${name} must reference JSON`)
  return result
}
function scenarioNames(value) {
  const names = text('LLM_SCENARIOS', value).split(',').map((name) => name.trim()).filter(Boolean)
  if (names.length === 0 || names.some((name) => !SCENARIOS.has(name))
    || new Set(names).size !== names.length) throw new Error('LLM_SCENARIOS is invalid')
  return Object.freeze(names)
}

export function loadSearchLlmConfig(env) {
  const targetEnv = text('TARGET_ENV', env.TARGET_ENV)
  const baseUrl = text('BASE_URL', env.BASE_URL).replace(/\/+$/, '')
  const allowedHosts = text('ALLOWED_HOSTS', env.ALLOWED_HOSTS).split(',').map((v) => v.trim().toLowerCase()).filter(Boolean)
  assertSafeTarget(targetEnv, baseUrl, allowedHosts)
  if (targetEnv !== 'staging' || env.STAGING_APPROVED !== 'true') throw new Error('approved staging is required')
  if (env.LLM_LIVE_TEST_APPROVED !== 'true') throw new Error('LLM_LIVE_TEST_APPROVED=true is required')
  const sha = validateShaEvidence({
    targetEnv, commitSha: env.COMMIT_SHA, harnessCommitSha: env.HARNESS_COMMIT_SHA,
    stagingSplitApproved: env.STAGING_SPLIT_SHA_APPROVED === 'true',
    harnessSourceVerified: env.STAGING_HARNESS_SOURCE_VERIFIED === 'true',
  })
  const limits = Object.freeze({
    durationSeconds: parsePositiveInt('LLM_DURATION_SECONDS', env.LLM_DURATION_SECONDS, 600),
  })
  const budget = validateBudget({
    plannedCalls: integer('LLM_PLANNED_CALLS', env.LLM_PLANNED_CALLS),
    cumulativeCalls: integer('LLM_CUMULATIVE_CALLS', env.LLM_CUMULATIVE_CALLS),
    plannedCostUsd: decimal('LLM_PLANNED_COST_USD', env.LLM_PLANNED_COST_USD),
    cumulativeCostUsd: decimal('LLM_CUMULATIVE_COST_USD', env.LLM_CUMULATIVE_COST_USD),
  })
  const scenarios = scenarioNames(env.LLM_SCENARIOS)
  const fallbackMode = scenarios.includes('fallback') ? text('LLM_FALLBACK_MODE', env.LLM_FALLBACK_MODE) : null
  if (fallbackMode !== null && !['disabled', 'timeout'].includes(fallbackMode)) {
    throw new Error('LLM_FALLBACK_MODE must be disabled or timeout')
  }
  return Object.freeze({ targetEnv, baseUrl, allowedHosts: Object.freeze(allowedHosts),
    runId: runId(env.LLM_RUN_ID), fixturePath: jsonPath('LLM_FIXTURE_PATH', env.LLM_FIXTURE_PATH),
    budgetProofPath: jsonPath('LLM_BUDGET_PROOF_PATH', env.LLM_BUDGET_PROOF_PATH),
    scenarios, fallbackMode, limits, budget, ...sha })
}
