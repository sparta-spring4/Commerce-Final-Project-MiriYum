const MAX_CALLS = 200
const MAX_COST_USD = 0.50

function nonNegative(name, value, integer = false) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0
    || (integer && !Number.isInteger(value))) {
    throw new Error(`${name} must be a non-negative ${integer ? 'integer' : 'number'}`)
  }
  return value
}

export function validateBudget(input) {
  const plannedCalls = nonNegative('plannedCalls', input.plannedCalls, true)
  const cumulativeCalls = nonNegative('cumulativeCalls', input.cumulativeCalls, true)
  const plannedCostUsd = nonNegative('plannedCostUsd', input.plannedCostUsd)
  const cumulativeCostUsd = nonNegative('cumulativeCostUsd', input.cumulativeCostUsd)
  const totalCalls = plannedCalls + cumulativeCalls
  const totalCostUsd = plannedCostUsd + cumulativeCostUsd
  if (totalCalls > MAX_CALLS) throw new Error('LLM call budget exceeds 200')
  if (totalCostUsd > MAX_COST_USD + Number.EPSILON) throw new Error('LLM cost budget exceeds 0.50 USD')
  return Object.freeze({
    plannedCalls, cumulativeCalls, plannedCostUsd, cumulativeCostUsd,
    totalCalls, totalCostUsd, remainingCalls: MAX_CALLS - totalCalls,
    remainingCostUsd: Math.max(0, MAX_COST_USD - totalCostUsd),
  })
}

export function validateBudgetProof(proof, expected) {
  if (proof === null || typeof proof !== 'object' || Array.isArray(proof)
    || proof.schemaVersion !== 'miriyum-k6-search-llm-budget-v1'
    || proof.approved !== true
    || proof.runId !== expected.runId
    || proof.commitSha !== expected.commitSha
    || proof.harnessCommitSha !== expected.harnessCommitSha) {
    throw new Error('LLM budget proof identity is invalid')
  }
  for (const name of ['plannedCalls', 'cumulativeCalls', 'plannedCostUsd', 'cumulativeCostUsd']) {
    if (proof[name] !== expected.budget[name]) throw new Error('LLM budget proof values do not match execution')
  }
  return validateBudget(proof)
}
