import { check } from 'k6'
import { validateBudget, validateBudgetProof } from '../search-llm/budget.js'

export const options = { thresholds: { checks: ['rate==1'] } }
function throws(action) { try { action(); return false } catch (_) { return true } }

export default function () {
  check(null, {
    'bounded aggregate budget is accepted': () => validateBudget({ plannedCalls: 150, cumulativeCalls: 20, plannedCostUsd: 0.3, cumulativeCostUsd: 0.1 }).remainingCalls === 30,
    '200 calls is inclusive': () => !throws(() => validateBudget({ plannedCalls: 180, cumulativeCalls: 20, plannedCostUsd: 0.3, cumulativeCostUsd: 0.1 })),
    'call overflow fails before execution': () => throws(() => validateBudget({ plannedCalls: 181, cumulativeCalls: 20, plannedCostUsd: 0.3, cumulativeCostUsd: 0.1 })),
    'cost overflow fails before execution': () => throws(() => validateBudget({ plannedCalls: 1, cumulativeCalls: 0, plannedCostUsd: 0.31, cumulativeCostUsd: 0.2 })),
    'negative evidence is rejected': () => throws(() => validateBudget({ plannedCalls: -1, cumulativeCalls: 0, plannedCostUsd: 0, cumulativeCostUsd: 0 })),
    'approved proof is bound to run and both SHAs': () => validateBudgetProof({ schemaVersion: 'miriyum-k6-search-llm-budget-v1', approved: true, runId: 'run-01', commitSha: 'a'.repeat(40), harnessCommitSha: 'b'.repeat(40), plannedCalls: 10, cumulativeCalls: 20, plannedCostUsd: 0.1, cumulativeCostUsd: 0.2 }, { runId: 'run-01', commitSha: 'a'.repeat(40), harnessCommitSha: 'b'.repeat(40), budget: { plannedCalls: 10, cumulativeCalls: 20, plannedCostUsd: 0.1, cumulativeCostUsd: 0.2 } }).totalCalls === 30,
    'proof from another run is rejected': () => throws(() => validateBudgetProof({ schemaVersion: 'miriyum-k6-search-llm-budget-v1', approved: true, runId: 'other', commitSha: 'a'.repeat(40), harnessCommitSha: 'b'.repeat(40), plannedCalls: 10, cumulativeCalls: 20, plannedCostUsd: 0.1, cumulativeCostUsd: 0.2 }, { runId: 'run-01', commitSha: 'a'.repeat(40), harnessCommitSha: 'b'.repeat(40), budget: { plannedCalls: 10, cumulativeCalls: 20, plannedCostUsd: 0.1, cumulativeCostUsd: 0.2 } })),
  })
}
