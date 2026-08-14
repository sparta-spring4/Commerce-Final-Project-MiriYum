import { check } from 'k6'
import execution from 'k6/execution'
import http from 'k6/http'

import { loadConfig } from './config.js'
import { validateFixture } from './lib/contracts.js'
import { renderSafeSummary } from './lib/summary.js'
import { loginConsumer, runAuthRefresh } from './scenarios/auth-refresh.js'
import { runNotificationHistory } from './scenarios/notification-history.js'
import { runReservationCreate } from './scenarios/reservation-create.js'
import { runStoreSearch } from './scenarios/store-search.js'

const config = loadConfig(__ENV)
const fixture = validateFixture(JSON.parse(open(config.fixturePath)))

function allocate(total, index, count) {
  return Math.floor(total / count) + (index < total % count ? 1 : 0)
}

function buildScenarios() {
  const scenarios = {}
  const count = config.scenarioNames.length
  config.scenarioNames.forEach((name, index) => {
    if (config.profile === 'smoke') {
      scenarios[name] = {
        executor: 'shared-iterations',
        exec: name,
        vus: 1,
        iterations: name === 'notificationHistory'
          ? fixture.notification.accountAliases.length
          : 1,
        maxDuration: '1m',
        gracefulStop: '5s',
        tags: { profile: config.profile, target_env: config.targetEnv },
      }
      return
    }
    scenarios[name] = {
      executor: 'constant-arrival-rate',
      exec: name,
      rate: allocate(config.limits.arrivalRate, index, count),
      timeUnit: '1s',
      duration: `${config.limits.durationSeconds}s`,
      preAllocatedVUs: allocate(config.limits.maxVus, index, count),
      maxVUs: allocate(config.limits.maxVus, index, count),
      gracefulStop: '30s',
      tags: { profile: config.profile, target_env: config.targetEnv },
    }
  })
  return scenarios
}

function buildThresholds() {
  const thresholds = {}
  for (const scenario of config.scenarioNames) {
    const tags = `phase:measured,scenario:${scenario}`
    thresholds[`checks{${tags}}`] = ['rate==1']
    thresholds[`http_req_duration{${tags}}`] = ['max>=0']
    thresholds[`http_req_failed{${tags}}`] = ['rate>=0']
    thresholds[`http_reqs{${tags}}`] = ['count>=0']
    thresholds[`expected_4xx{${tags}}`] = ['count>=0']
    thresholds[`unexpected_4xx{${tags}}`] = ['count==0']
    thresholds[`server_5xx{${tags}}`] = ['count==0']
    thresholds[`unexpected_status{${tags}}`] = ['count==0']
  }
  return thresholds
}

function validateExecutionCapacity() {
  if (config.profile !== 'smoke'
      && config.scenarioNames.includes('authRefresh')
      && fixture.accounts.length < config.limits.maxVus) {
    throw new Error('baseline fixture requires at least one synthetic account per maximum VU')
  }
  if (!config.scenarioNames.includes('reservationCreate')) return
  const reservationIndex = config.scenarioNames.indexOf('reservationCreate')
  const requiredTemplates = config.profile === 'smoke'
    ? 1
    : allocate(config.limits.arrivalRate, reservationIndex, config.scenarioNames.length)
      * config.limits.durationSeconds
  if (fixture.reservationTemplates.length < requiredTemplates) {
    throw new Error(`reservation fixture requires ${requiredTemplates} non-conflicting templates`)
  }
}

validateExecutionCapacity()

export const options = {
  scenarios: buildScenarios(),
  thresholds: buildThresholds(),
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  summaryTimeUnit: 'ms',
  userAgent: `miriyum-k6-baseline/${config.commitSha.slice(0, 12)}`,
}

function resolveCredentials(reference) {
  const email = __ENV[reference.emailEnv]
  const password = __ENV[reference.passwordEnv]
  if (typeof email !== 'string' || email.trim() === '') {
    throw new Error(`synthetic account ${reference.alias} email environment value is missing`)
  }
  if (typeof password !== 'string' || password === '') {
    throw new Error(`synthetic account ${reference.alias} password environment value is missing`)
  }
  return { email, password }
}

function needsPreparedBearer() {
  return config.scenarioNames.includes('reservationCreate')
    || config.scenarioNames.includes('notificationHistory')
}

function preparedAccountAliases() {
  const aliases = new Set()
  if (config.scenarioNames.includes('reservationCreate')) {
    fixture.reservationTemplates.forEach((template) => aliases.add(template.accountAlias))
  }
  if (config.scenarioNames.includes('notificationHistory')) {
    fixture.notification.accountAliases.forEach((alias) => aliases.add(alias))
  }
  return aliases
}

export function setup() {
  const tokensByAlias = {}
  if (needsPreparedBearer()) {
    const aliases = preparedAccountAliases()
    for (const reference of fixture.accounts.filter((account) => aliases.has(account.alias))) {
      tokensByAlias[reference.alias] = loginConsumer({
        client: http,
        baseUrl: config.baseUrl,
        account: resolveCredentials(reference),
        tags: { phase: 'preparation' },
      })
    }
  }
  return { tokensByAlias }
}

function executeScenario(name, action) {
  let result
  try {
    result = action()
  } catch (_) {
    check(null, { [`${name} contract remains valid`]: () => false }, { phase: 'measured' })
    return
  }
  const accepted = result.classification === 'success'
    || result.classification === 'expected_4xx'
  check(result, { [`${name} result is accepted`]: () => accepted }, { phase: 'measured' })
}

function accountForCurrentVu() {
  return fixture.accounts[(execution.vu.idInTest - 1) % fixture.accounts.length]
}

function preparedToken(data, alias) {
  const token = data && data.tokensByAlias ? data.tokensByAlias[alias] : undefined
  if (typeof token !== 'string' || token === '') {
    throw new Error(`prepared bearer for ${alias} is missing`)
  }
  return token
}

export function authRefresh() {
  executeScenario('authRefresh', () => {
    const account = accountForCurrentVu()
    return runAuthRefresh({
      client: http,
      baseUrl: config.baseUrl,
      allowedOrigin: fixture.allowedOrigin,
      account: resolveCredentials(account),
    })
  })
}

export function storeSearch() {
  executeScenario('storeSearch', () => runStoreSearch({
    client: http,
    baseUrl: config.baseUrl,
    search: fixture.search,
  }))
}

export function reservationCreate(data) {
  executeScenario('reservationCreate', () => {
    const template = fixture.reservationTemplates[execution.scenario.iterationInTest]
    return runReservationCreate({
      client: http,
      baseUrl: config.baseUrl,
      accessToken: preparedToken(data, template.accountAlias),
      template,
      runId: config.runId,
      vu: execution.vu.idInTest,
      iteration: execution.scenario.iterationInTest,
    })
  })
}

export function notificationHistory(data) {
  executeScenario('notificationHistory', () => {
    const alias = fixture.notification.accountAliases[
      execution.scenario.iterationInTest % fixture.notification.accountAliases.length
    ]
    return runNotificationHistory({
      client: http,
      baseUrl: config.baseUrl,
      accessToken: preparedToken(data, alias),
      pageSize: fixture.notification.pageSize,
    })
  })
}

export function handleSummary(data) {
  const rendered = renderSafeSummary(data, {
    targetEnv: config.targetEnv,
    profile: config.profile,
    runId: config.runId,
    commitSha: config.commitSha,
    limits: config.limits,
  })
  return {
    stdout: rendered.stdout,
    [`/results/${config.runId}.json`]: rendered.json,
    [`/results/${config.runId}.md`]: rendered.markdown,
  }
}
