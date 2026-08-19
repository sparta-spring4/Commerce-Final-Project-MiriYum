import { check } from 'k6'

import {
  buildSseTargets,
  endpointPath,
  validateChangedEvent,
  validateSseFixture,
} from '../sse/contracts.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function fixture() {
  return {
    allowedOrigin: 'https://loadtest-proxy:8443',
    consumerAccounts: [
      {
        alias: 'consumer-01',
        emailEnv: 'K6_CONSUMER_01_EMAIL',
        passwordEnv: 'K6_CONSUMER_01_PASSWORD',
      },
      {
        alias: 'consumer-02',
        emailEnv: 'K6_CONSUMER_02_EMAIL',
        passwordEnv: 'K6_CONSUMER_02_PASSWORD',
      },
    ],
    storeOperatorAccounts: [
      {
        alias: 'operator-01',
        emailEnv: 'K6_OPERATOR_01_EMAIL',
        passwordEnv: 'K6_OPERATOR_01_PASSWORD',
        storeIds: ['301', '302'],
      },
    ],
    scopes: {
      notificationConsumerAliases: ['consumer-01'],
      waitingConsumerAliases: ['consumer-02'],
      waitingStoreOperatorTargets: [
        { accountAlias: 'operator-01', storeId: '301' },
      ],
    },
  }
}

function errorMessage(action) {
  try {
    action()
    return null
  } catch (error) {
    return error.message
  }
}

export default function () {
  check(null, {
    'complete SSE fixture is accepted without credential values': () => {
      const result = validateSseFixture(fixture())
      return result.valid === true && Object.keys(result).length === 1
    },
    'duplicate account alias is rejected': () => {
      const value = fixture()
      value.consumerAccounts.push({ ...value.consumerAccounts[0] })
      return errorMessage(() => validateSseFixture(value))?.includes('alias') === true
    },
    'inline password field is rejected': () => {
      const value = fixture()
      value.consumerAccounts[0].password = 'forbidden-inline-secret'
      return errorMessage(() => validateSseFixture(value))?.includes('field') === true
    },
    'malformed credential environment reference is rejected': () => {
      const value = fixture()
      value.consumerAccounts[0].passwordEnv = 'plain_password'
      return errorMessage(() => validateSseFixture(value))?.includes('passwordEnv') === true
    },
    'non-positive store ID is rejected': () => {
      const value = fixture()
      value.storeOperatorAccounts[0].storeIds = ['0']
      value.scopes.waitingStoreOperatorTargets[0].storeId = '0'
      return errorMessage(() => validateSseFixture(value))?.includes('storeId') === true
    },
    'cross-namespace alias reuse is rejected': () => {
      const value = fixture()
      value.storeOperatorAccounts[0].alias = 'consumer-01'
      value.scopes.waitingStoreOperatorTargets[0].accountAlias = 'consumer-01'
      return errorMessage(() => validateSseFixture(value))?.includes('namespace') === true
    },
    'fixture without selected scope capacity is rejected': () => {
      const value = fixture()
      value.scopes.notificationConsumerAliases = []
      value.scopes.waitingConsumerAliases = []
      value.scopes.waitingStoreOperatorTargets = []
      return errorMessage(() => validateSseFixture(value))?.includes('scope') === true
    },
    'scope must reference an owned store': () => {
      const value = fixture()
      value.scopes.waitingStoreOperatorTargets[0].storeId = '999'
      return errorMessage(() => validateSseFixture(value))?.includes('owned') === true
    },
    'target builder allocates the requested account capacity without leaking credentials': () => {
      const targets = buildSseTargets(fixture(), 2)
      const serialized = JSON.stringify(targets)
      return targets.length === 6
        && targets.every((target) => Object.isFrozen(target))
        && !serialized.includes('emailEnv')
        && !serialized.includes('passwordEnv')
    },
    'target builder rejects per-account capacity above the runtime ceiling': () =>
      errorMessage(() => buildSseTargets(fixture(), 7))?.includes('connectionsPerAccount') === true,
    'wrong event name is rejected for the endpoint kind': () =>
      errorMessage(() => validateChangedEvent({
        name: 'waiting.changed',
        data: '{}',
        id: 'opaque_cursor-1',
      }, 'notification-consumer'))?.includes('name') === true,
    'non-empty event data is rejected': () =>
      errorMessage(() => validateChangedEvent({
        name: 'notifications.changed',
        data: '{"notificationId":1}',
        id: 'opaque_cursor-1',
      }, 'notification-consumer'))?.includes('data') === true,
    'missing event ID is rejected': () =>
      errorMessage(() => validateChangedEvent({
        name: 'notifications.changed',
        data: '{}',
      }, 'notification-consumer'))?.includes('id') === true,
    'valid changed event returns no cursor material': () => {
      const result = validateChangedEvent({
        name: 'waiting.changed',
        data: '{}',
        id: 'opaque_cursor-1',
      }, 'waiting-consumer')
      return result.valid === true
        && Object.keys(result).length === 1
        && !JSON.stringify(result).includes('opaque_cursor-1')
    },
    'endpoint paths match the three owned SSE routes': () => {
      const paths = [
        endpointPath({ kind: 'notification-consumer' }),
        endpointPath({ kind: 'waiting-consumer' }),
        endpointPath({ kind: 'waiting-store-operator', storeId: '301' }),
      ]
      return paths[0] === '/api/v1/consumers/me/notification-events'
        && paths[1] === '/api/v1/consumers/me/waiting-events'
        && paths[2] === '/api/v1/store-operators/stores/301/waiting-events'
    },
  })
}
