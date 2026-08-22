import { check, sleep } from 'k6'
import execution from 'k6/execution'
import http from 'k6/http'
import { Counter } from 'k6/metrics'

import { createFixtureFingerprint } from '../lib/smoke-proof.js'
import { validateBudgetProof } from './budget.js'
import { loadSearchLlmConfig } from './config.js'
import { validateSearchLlmFixture } from './contracts.js'
import { buildSearchLlmScenarios, runSearchLlmCase } from './scenarios.js'
import { renderSafeSearchLlmSummary } from './summary.js'

const unexpected4xx = new Counter('unexpected_4xx')
const server5xx = new Counter('server_5xx')
const unexpectedStatus = new Counter('unexpected_status')
const config = loadSearchLlmConfig(__ENV)
const fixtureText = open(config.fixturePath)
const fixture = validateSearchLlmFixture(JSON.parse(fixtureText))
const fixtureSha256 = createFixtureFingerprint(fixtureText)
validateBudgetProof(JSON.parse(open(config.budgetProofPath)), config)

export const options = {
  scenarios: buildSearchLlmScenarios(config, fixture),
  thresholds: {
    'checks{phase:measured}': ['rate==1'],
    'unexpected_4xx{phase:measured}': ['count==0'],
    'server_5xx{phase:measured}': ['count==0'],
    'unexpected_status{phase:measured}': ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  summaryTimeUnit: 'ms', userAgent: `miriyum-k6-search-llm/${config.harnessCommitSha.slice(0, 12)}`,
  systemTags: ['status', 'method', 'name', 'scenario'],
}

export function executeSearchLlmCase() {
  const cases = fixture.cases.filter((entry) => entry.scenario === execution.scenario.name)
  const fixtureCase = cases[execution.scenario.iterationInTest]
  let accepted = false
  try {
    const result = runSearchLlmCase({ client: http, baseUrl: config.baseUrl, fixtureCase })
    accepted = result.completed === true
    if (result.status >= 400 && result.status < 500) unexpected4xx.add(1)
    else if (result.status >= 500) server5xx.add(1)
    else if (result.status !== 200) unexpectedStatus.add(1)
  } catch (_) {
    unexpectedStatus.add(1)
  }
  check(accepted, { [`${execution.scenario.name} contract is valid`]: (value) => value }, { phase: 'measured', scenario: execution.scenario.name })
  sleep(1)
}

export function handleSummary(data) {
  const rendered = renderSafeSearchLlmSummary(data, {
    targetEnv: config.targetEnv, runId: config.runId, commitSha: config.commitSha,
    harnessCommitSha: config.harnessCommitSha, fixtureSha256, scenarios: config.scenarios,
    budget: config.budget,
    // CloudWatch is outside k6's runtime surface. Never claim zero; attach observed
    // before/after deltas to the external run evidence.
    llmDeltas: null,
  })
  return { stdout: rendered.stdout, [`/results/${config.runId}.json`]: rendered.json, [`/results/${config.runId}.md`]: rendered.markdown }
}
