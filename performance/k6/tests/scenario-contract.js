import { check } from 'k6'

import * as fixtureContracts from '../lib/contracts.js'
import { loginConsumer, runAuthRefresh } from '../scenarios/auth-refresh.js'
import { runNotificationHistory } from '../scenarios/notification-history.js'
import { runReservationCreate } from '../scenarios/reservation-create.js'
import { runStoreSearch } from '../scenarios/store-search.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function envelope(data) {
  return JSON.stringify({ code: 'SUCCESS', message: 'ok', data })
}

function throws(action) {
  try {
    action()
    return false
  } catch (_) {
    return true
  }
}

function notificationItem(notificationId) {
  return {
    notificationId,
    purpose: 'RESERVATION_CONFIRMED',
    title: '예약이 확정되었습니다',
    resource: { type: 'RESERVATION', id: '9001' },
    occurredAt: '2026-08-14T12:00:00+09:00',
    createdAt: '2026-08-14T12:00:00+09:00',
    deliveredAt: '2026-08-14T12:00:01+09:00',
    action: null,
  }
}

function reservationConflictClient(code) {
  return {
    post() {
      return {
        status: 409,
        body: JSON.stringify({ code, message: 'conflict' }),
        headers: {},
      }
    },
  }
}

function validSearchData() {
  return {
    items: [{
      storeId: '301',
      name: 'Synthetic Store',
      region: 'SEOUL',
      address: 'Synthetic address 1',
      storeCategoryCode: 'KOREAN_FOOD',
      operationStatus: 'OPEN',
      modes: {
        reservationEnabled: true,
        menuHoldEnabled: true,
        pickupEnabled: false,
      },
      reservationAvailability: 'NOT_REQUESTED',
      coordinates: null,
      recommendationReason: null,
    }],
    normalizedCondition: {
      regionCodes: ['SEOUL'],
      storeCategoryCodes: [],
      menuCategoryCodes: [],
      tagCodes: [],
      minimumPrice: null,
      maximumPrice: null,
      partySize: null,
      reservationDate: null,
      reservationTime: null,
      remainingKeyword: '',
    },
    warnings: [],
    ruleVersion: 'rules-v1',
    vocabularyVersion: 'vocabulary-v1',
    rankingRuleVersion: null,
    nextCursor: null,
  }
}

function validReservationData() {
  return {
    reservationId: '9001',
    storeId: '301',
    storeName: 'Synthetic Store',
    serviceDate: '2026-08-20',
    timeStatus: 'RESOLVED',
    startAt: '2026-08-20T18:00:00+09:00',
    serviceEndAt: '2026-08-20T19:00:00+09:00',
    timeZoneId: 'Asia/Seoul',
    party: { adultCount: 2, childCount: 0, infantCount: 0, totalCount: 2 },
    status: 'CONFIRMED',
    menuSelections: [],
    createdAt: '2026-08-14T12:00:00+09:00',
    cancelledBy: null,
    cancellationReason: null,
  }
}

function reservationCapacityFixture(templateCount) {
  return {
    allowedOrigin: 'http://localhost:5173',
    accounts: [
      {
        alias: 'auth-contract',
        emailEnv: 'K6_AUTH_CONTRACT_EMAIL',
        passwordEnv: 'K6_AUTH_CONTRACT_PASSWORD',
      },
      {
        alias: 'reservation-contract',
        emailEnv: 'K6_RESERVATION_CONTRACT_EMAIL',
        passwordEnv: 'K6_RESERVATION_CONTRACT_PASSWORD',
      },
      {
        alias: 'notification-contract-1',
        emailEnv: 'K6_NOTIFICATION_CONTRACT_1_EMAIL',
        passwordEnv: 'K6_NOTIFICATION_CONTRACT_1_PASSWORD',
      },
      {
        alias: 'notification-contract-2',
        emailEnv: 'K6_NOTIFICATION_CONTRACT_2_EMAIL',
        passwordEnv: 'K6_NOTIFICATION_CONTRACT_2_PASSWORD',
      },
    ],
    auth: { accountAliases: ['auth-contract'] },
    search: { input: '서울 한식' },
    reservationTemplates: Array.from({ length: templateCount }, (_, index) => ({
      accountAlias: 'reservation-contract',
      storeId: '301',
      serviceDate: `2026-09-${String(Math.floor(index / 24) + 1).padStart(2, '0')}`,
      startTime: `${String(index % 24).padStart(2, '0')}:00:00`,
      startOffset: '+09:00',
      party: { adultCount: 2, childCount: 0, infantCount: 0 },
      menuSelections: [],
    })),
    notification: {
      accountAliases: ['notification-contract-1', 'notification-contract-2'],
      pageSize: 2,
      minimumDeliveredItemsPerAccount: 3,
    },
  }
}

function reservationFixtureWithNormalizedDuplicate(templateCount) {
  const fixture = reservationCapacityFixture(templateCount)
  const originalIndex = templateCount - 2
  const duplicateIndex = templateCount - 1
  fixture.reservationTemplates[originalIndex].menuSelections = [
    { menuId: '701', quantity: 1 },
    { menuId: '702', quantity: 2 },
  ]
  fixture.reservationTemplates[duplicateIndex] = {
    ...fixture.reservationTemplates[originalIndex],
    menuSelections: [
      { menuId: '702', quantity: 1 },
      { menuId: '701', quantity: 1 },
      { menuId: '702', quantity: 1 },
    ],
  }
  return fixture
}

function reservationFixtureWithZeroOffsetDuplicate(templateCount) {
  const fixture = reservationCapacityFixture(templateCount)
  const originalIndex = templateCount - 2
  const duplicateIndex = templateCount - 1
  fixture.reservationTemplates[originalIndex].startOffset = '+00:00'
  fixture.reservationTemplates[duplicateIndex] = {
    ...fixture.reservationTemplates[originalIndex],
    startOffset: '-00:00',
  }
  return fixture
}

function responseClient(status, data) {
  return {
    get() {
      return { status, body: envelope(data), headers: {} }
    },
    post() {
      return { status, body: envelope(data), headers: {} }
    },
  }
}

function rateLimitedAuthClient() {
  return {
    post() {
      return {
        status: 429,
        body: JSON.stringify({ code: 'AUTH_009', message: 'rate limited', data: null }),
        headers: {},
      }
    },
  }
}

function notificationPagesClient(pages) {
  let index = 0
  return {
    get() {
      const data = pages[Math.min(index, pages.length - 1)]
      index += 1
      return { status: 200, body: envelope(data), headers: {} }
    },
  }
}

function notificationContractThrows(pages) {
  return throws(() => runNotificationHistory({
    client: notificationPagesClient(pages),
    baseUrl: 'http://backend:8080',
    accessToken: 'access-token-value',
    pageSize: 2,
    minimumDeliveredItemsPerAccount: 3,
    tags: { phase: 'measured' },
  }))
}

const RESERVATION_INPUT = {
  baseUrl: 'http://backend:8080',
  accessToken: 'access-token-value',
  template: {
    storeId: '301',
    serviceDate: '2026-08-20',
    startTime: '18:00:00',
    startOffset: '+09:00',
    party: { adultCount: 2, childCount: 0, infantCount: 0 },
    menuSelections: [],
  },
  runId: 'local-smoke-20260814',
  vu: 1,
  iteration: 2,
  tags: { phase: 'measured' },
}

class RecordingClient {
  constructor() {
    this.calls = []
    this.notificationPage = 0
  }

  post(url, body, params) {
    this.calls.push({ method: 'POST', url, body, ...params })
    if (url.endsWith('/api/v1/consumers/auth/sessions')) {
      return {
        status: 200,
        body: envelope({ accessToken: 'access-token-value', tokenType: 'Bearer', expiresIn: 900 }),
        headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=refresh-cookie-value' },
      }
    }
    if (url.endsWith('/api/v1/consumers/me/reservations')) {
      return {
        status: 201,
        body: envelope(validReservationData()),
        headers: {},
      }
    }
    return {
      status: 200,
      body: envelope({ accessToken: 'rotated-access-token', tokenType: 'Bearer', expiresIn: 900 }),
      headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=rotated-refresh-cookie' },
    }
  }

  get(url, params) {
    this.calls.push({ method: 'GET', url, ...params })
    if (url.endsWith('/api/v1/consumers/auth/csrf-tokens/current')) {
      return {
        status: 200,
        body: envelope({ token: 'csrf-token-value', headerName: 'X-CSRF-TOKEN' }),
        headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_CSRF=csrf-token-value' },
      }
    }
    if (url.includes('/api/v1/consumers/me/notifications')) {
      this.notificationPage += 1
      return {
        status: 200,
        body: envelope(this.notificationPage === 1
          ? { items: [notificationItem('11'), notificationItem('10')], hasNext: true, nextCursor: 'opaque_cursor_1' }
          : { items: [notificationItem('9')], hasNext: false, nextCursor: null }),
        headers: {},
      }
    }
    return {
      status: 200,
      body: envelope(validSearchData()),
      headers: {},
    }
  }

  del(url, body, params) {
    this.calls.push({ method: 'DELETE', url, body, ...params })
    return {
      status: 200,
      body: envelope(null),
      headers: {
        'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=; Max-Age=0',
      },
    }
  }
}

class MalformedLoginClient extends RecordingClient {
  post(url, body, params) {
    if (url.endsWith('/api/v1/consumers/auth/sessions')) {
      this.calls.push({ method: 'POST', url, body, ...params })
      return {
        status: 200,
        body: envelope({ tokenType: 'Bearer', expiresIn: 900 }),
        headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=refresh-cookie-value' },
      }
    }
    return super.post(url, body, params)
  }
}

export default function () {
  const requiredReservationTemplateCount = fixtureContracts.requiredReservationTemplateCount
  const buildExecutionScenarios = fixtureContracts.buildExecutionScenarios
  const singleScenarioRequired = typeof requiredReservationTemplateCount === 'function'
    ? requiredReservationTemplateCount({
      profile: 'local-baseline',
      totalArrivalRate: 1,
      durationSeconds: 30,
      scenarioIndex: 0,
      scenarioCount: 1,
    })
    : null
  const mixedScenarioFirstRequired = typeof requiredReservationTemplateCount === 'function'
    ? requiredReservationTemplateCount({
      profile: 'local-baseline',
      totalArrivalRate: 4,
      durationSeconds: 30,
      scenarioIndex: 0,
      scenarioCount: 3,
    })
    : null
  const mixedScenarioThirdRequired = typeof requiredReservationTemplateCount === 'function'
    ? requiredReservationTemplateCount({
      profile: 'local-baseline',
      totalArrivalRate: 4,
      durationSeconds: 30,
      scenarioIndex: 2,
      scenarioCount: 3,
    })
    : null
  const singleScenarioConfig = {
    profile: 'local-baseline',
    targetEnv: 'local',
    scenarioNames: ['reservationCreate'],
    limits: { arrivalRate: 1, durationSeconds: 30, maxVus: 1 },
  }
  const oneShortFixtureRejected = typeof buildExecutionScenarios === 'function'
    && Number.isInteger(singleScenarioRequired)
    && throws(() => buildExecutionScenarios(
      singleScenarioConfig,
      fixtureContracts.validateFixture(reservationCapacityFixture(singleScenarioRequired - 1)),
    ))
  const sufficientScenarios = typeof buildExecutionScenarios === 'function'
    && Number.isInteger(singleScenarioRequired)
    ? buildExecutionScenarios(
      singleScenarioConfig,
      fixtureContracts.validateFixture(reservationCapacityFixture(singleScenarioRequired)),
    )
    : null
  const normalizedDuplicateFixtureRejected = typeof buildExecutionScenarios === 'function'
    && Number.isInteger(singleScenarioRequired)
    && throws(() => buildExecutionScenarios(
      singleScenarioConfig,
      fixtureContracts.validateFixture(
        reservationFixtureWithNormalizedDuplicate(singleScenarioRequired),
      ),
    ))
  const zeroOffsetDuplicateFixtureRejected = typeof buildExecutionScenarios === 'function'
    && Number.isInteger(singleScenarioRequired)
    && throws(() => buildExecutionScenarios(
      singleScenarioConfig,
      fixtureContracts.validateFixture(
        reservationFixtureWithZeroOffsetDuplicate(singleScenarioRequired),
      ),
    ))
  const mixedScenarioFirstScenarios = typeof buildExecutionScenarios === 'function'
    ? buildExecutionScenarios({
      ...singleScenarioConfig,
      scenarioNames: ['reservationCreate', 'storeSearch', 'notificationHistory'],
      limits: { arrivalRate: 4, durationSeconds: 30, maxVus: 3 },
    }, fixtureContracts.validateFixture(reservationCapacityFixture(61)))
    : null
  const mixedScenarioThirdScenarios = typeof buildExecutionScenarios === 'function'
    ? buildExecutionScenarios({
      ...singleScenarioConfig,
      scenarioNames: ['storeSearch', 'notificationHistory', 'reservationCreate'],
      limits: { arrivalRate: 4, durationSeconds: 30, maxVus: 3 },
    }, fixtureContracts.validateFixture(reservationCapacityFixture(31)))
    : null
  const client = new RecordingClient()
  const authResult = runAuthRefresh({
    client,
    baseUrl: 'http://backend:8080',
    allowedOrigin: 'http://localhost:5173',
    account: { email: 'consumer@example.test', password: 'synthetic-password' },
    tags: { phase: 'measured' },
  })
  const searchResult = runStoreSearch({
    client,
    baseUrl: 'http://backend:8080',
    search: { input: '서울 한식' },
    tags: { phase: 'measured' },
  })
  const reservationResult = runReservationCreate({
    client,
    ...RESERVATION_INPUT,
  })
  const notificationResult = runNotificationHistory({
    client,
    baseUrl: 'http://backend:8080',
    accessToken: 'access-token-value',
    pageSize: 2,
    minimumDeliveredItemsPerAccount: 3,
    tags: { phase: 'measured' },
  })

  const loginCall = client.calls.find((call) => call.tags.request === 'consumerLogin')
  const refreshCall = client.calls.find((call) => call.tags.request === 'consumerTokenRefresh')
  const csrfCall = client.calls.find((call) => call.tags.request === 'consumerCsrfToken')
  const logoutCall = client.calls.find((call) => call.tags.request === 'consumerLogout')
  const searchCall = client.calls.find((call) => call.tags.request === 'storeSearch')
  const reservationCall = client.calls.find((call) => call.tags.request === 'reservationCreate')
  const firstNotificationCall = client.calls.find(
    (call) => call.tags.request === 'notificationHistoryFirstPage',
  )
  const secondNotificationCall = client.calls.find(
    (call) => call.tags.request === 'notificationHistoryNextPage',
  )
  const combinedResult = JSON.stringify({
    authResult,
    searchResult,
    reservationResult,
    notificationResult,
  })
  const capacityConflict = runReservationCreate({
    client: reservationConflictClient('RESERVATION_003'),
    ...RESERVATION_INPUT,
  })
  const notificationConflict = runReservationCreate({
    client: reservationConflictClient('NOTIFICATION_002'),
    ...RESERVATION_INPUT,
  })
  const duplicateReservationConflict = runReservationCreate({
    client: reservationConflictClient('RESERVATION_004'),
    ...RESERVATION_INPUT,
  })
  const rateLimitedAuth = runAuthRefresh({
    client: rateLimitedAuthClient(),
    baseUrl: 'https://loadtest-proxy:8443',
    allowedOrigin: 'http://localhost:5173',
    account: { email: 'consumer@example.test', password: 'synthetic-password' },
    tags: { phase: 'measured' },
  })
  runAuthRefresh({
    client,
    baseUrl: 'http://backend:8080',
    allowedOrigin: 'http://localhost:5173',
    account: { email: 'consumer@example.test', password: 'synthetic-password' },
    tags: { phase: 'measured' },
  })
  const preparationClient = new RecordingClient()
  const preparedToken = loginConsumer({
    client: preparationClient,
    baseUrl: 'http://backend:8080',
    account: { email: 'prepared@example.test', password: 'synthetic-password' },
    tags: { phase: 'preparation' },
  })
  const preparationLogoutCall = preparationClient.calls.find(
    (call) => call.tags.request === 'consumerLogout',
  )
  const malformedScenarioClient = new MalformedLoginClient()
  const malformedScenarioRejected = throws(() => runAuthRefresh({
    client: malformedScenarioClient,
    baseUrl: 'http://backend:8080',
    allowedOrigin: 'http://localhost:5173',
    account: { email: 'malformed@example.test', password: 'synthetic-password' },
    tags: { phase: 'measured' },
  }))
  const malformedPreparationClient = new MalformedLoginClient()
  const malformedPreparationRejected = throws(() => loginConsumer({
    client: malformedPreparationClient,
    baseUrl: 'http://backend:8080',
    account: { email: 'malformed-prepared@example.test', password: 'synthetic-password' },
    tags: { phase: 'preparation' },
  }))

  check(null, {
    'smoke requires one reservation template': () =>
      typeof requiredReservationTemplateCount === 'function'
      && requiredReservationTemplateCount({
        profile: 'smoke',
        totalArrivalRate: 100,
        durationSeconds: 300,
        scenarioIndex: 0,
        scenarioCount: 1,
      }) === 1,
    'single reservation baseline includes the executor boundary iteration': () =>
      singleScenarioRequired === 31,
    'one fewer reservation template is rejected before execution': () =>
      oneShortFixtureRejected,
    'the exact boundary-inclusive reservation capacity is accepted': () =>
      sufficientScenarios?.reservationCreate.rate === 1
      && sufficientScenarios.reservationCreate.duration === '30s',
    'normalized duplicate reservation templates are rejected before execution': () =>
      normalizedDuplicateFixtureRejected,
    'equivalent zero-offset reservation templates are rejected before execution': () =>
      zeroOffsetDuplicateFixtureRejected,
    'mixed scenario allocation includes the remainder and boundary guard': () =>
      mixedScenarioFirstRequired === 61
      && mixedScenarioFirstScenarios?.reservationCreate.rate === 2,
    'mixed scenario capacity uses allocated rate instead of total rate': () =>
      mixedScenarioThirdRequired === 31
      && mixedScenarioThirdScenarios?.reservationCreate.rate === 1,
    'login uses the consumer session resource': () =>
      loginCall.method === 'POST'
      && loginCall.url === 'http://backend:8080/api/v1/consumers/auth/sessions',
    'login sends only the approved credential fields': () =>
      loginCall.body === '{"email":"consumer@example.test","password":"synthetic-password"}',
    'refresh sends empty JSON with the same approved Origin': () =>
      refreshCall.method === 'POST'
      && refreshCall.url === 'http://backend:8080/api/v1/consumers/auth/token-refreshes'
      && refreshCall.body === '{}'
      && refreshCall.headers.Origin === 'http://localhost:5173',
    'auth refresh obtains a CSRF token before logout': () =>
      csrfCall !== undefined
      && csrfCall.method === 'GET'
      && csrfCall.url === 'http://backend:8080/api/v1/consumers/auth/csrf-tokens/current'
      && csrfCall.tags.phase === 'cleanup',
    'auth refresh revokes the current session outside measured traffic': () =>
      logoutCall !== undefined
      && logoutCall.method === 'DELETE'
      && logoutCall.url === 'http://backend:8080/api/v1/consumers/auth/sessions/current'
      && logoutCall.body === null
      && logoutCall.headers['X-CSRF-TOKEN'] === 'csrf-token-value'
      && logoutCall.tags.phase === 'cleanup',
    'auth refresh reuses one CSRF token per client cookie jar': () =>
      client.calls.filter((call) => call.tags.request === 'consumerCsrfToken').length === 1
      && client.calls.filter((call) => call.tags.request === 'consumerLogout').length === 2,
    'search uses an encoded public searchInput': () =>
      searchCall.method === 'GET'
      && searchCall.url === 'http://backend:8080/api/v1/stores?searchInput=%EC%84%9C%EC%9A%B8%20%ED%95%9C%EC%8B%9D',
    'measurement and cleanup requests use separate phase tags': () =>
      client.calls.every((call) =>
        call.tags.phase === (['consumerCsrfToken', 'consumerLogout'].includes(call.tags.request)
          ? 'cleanup'
          : 'measured')),
    'scenario results expose statuses but not tokens or cookies': () =>
      authResult.loginStatus === 200
      && authResult.refreshStatus === 200
      && searchResult.status === 200
      && !combinedResult.includes('access-token-value')
      && !combinedResult.includes('refresh-cookie-value'),
    'reservation uses a UUID idempotency key': () =>
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
        reservationCall.headers['Idempotency-Key'],
      ),
    'reservation sends only the approved fixture fields': () =>
      reservationCall.body === '{"storeId":"301","serviceDate":"2026-08-20","startTime":"18:00:00","startOffset":"+09:00","party":{"adultCount":2,"childCount":0,"infantCount":0},"menuSelections":[]}',
    'reservation exposes no created resource identifier': () =>
      reservationResult.status === 201 && !combinedResult.includes('9001'),
    'approved capacity conflict is an expected 4xx': () =>
      capacityConflict.classification === 'expected_4xx',
    'reservation duplicate conflict is not hidden as an expected baseline result': () =>
      duplicateReservationConflict.classification === 'unexpected_4xx',
    'store search rejects an empty normalized condition': () =>
      throws(() => runStoreSearch({
        client: responseClient(200, { ...validSearchData(), normalizedCondition: {} }),
        baseUrl: 'http://backend:8080',
        search: { input: 'synthetic' },
      })),
    'store search rejects an item that violates the OpenAPI shape': () => {
      const invalidItem = { ...validSearchData().items[0] }
      delete invalidItem.address
      return throws(() => runStoreSearch({
        client: responseClient(200, { ...validSearchData(), items: [invalidItem] }),
        baseUrl: 'http://backend:8080',
        search: { input: 'synthetic' },
      }))
    },
    'store search rejects a non-calendar reservation date': () =>
      throws(() => runStoreSearch({
        client: responseClient(200, {
          ...validSearchData(),
          normalizedCondition: {
            ...validSearchData().normalizedCondition,
            reservationDate: '2026-99-99',
          },
        }),
        baseUrl: 'http://backend:8080',
        search: { input: 'synthetic' },
      })),
    'reservation creation rejects a compact non-OpenAPI success response': () =>
      throws(() => runReservationCreate({
        client: responseClient(201, {
          reservationId: '9001',
          storeId: '301',
          status: 'CONFIRMED',
        }),
        ...RESERVATION_INPUT,
      })),
    'reservation creation rejects a non-calendar service date': () =>
      throws(() => runReservationCreate({
        client: responseClient(201, {
          ...validReservationData(),
          serviceDate: '2026-02-30',
        }),
        ...RESERVATION_INPUT,
      })),
    'reservation creation rejects a non-RFC3339 timestamp': () =>
      throws(() => runReservationCreate({
        client: responseClient(201, {
          ...validReservationData(),
          createdAt: '2026-08-14T25:00:00+99:99',
        }),
        ...RESERVATION_INPUT,
      })),
    'rate-limited login is classified but does not complete auth refresh': () =>
      rateLimitedAuth.classification === 'expected_4xx'
      && rateLimitedAuth.completed === false,
    'prepared bearer login revokes its refresh family before returning the access token': () =>
      preparedToken === 'access-token-value'
      && preparationLogoutCall !== undefined
      && preparationLogoutCall.tags.phase === 'cleanup',
    'malformed successful auth login is rejected after session cleanup': () =>
      malformedScenarioRejected
      && malformedScenarioClient.calls.some((call) => call.tags.request === 'consumerLogout')
      && !malformedScenarioClient.calls.some(
        (call) => call.tags.request === 'consumerTokenRefresh',
      ),
    'malformed successful prepared login is rejected after session cleanup': () =>
      malformedPreparationRejected
      && malformedPreparationClient.calls.some((call) => call.tags.request === 'consumerLogout'),
    'notification invariant conflict is an unexpected 4xx': () =>
      notificationConflict.classification === 'unexpected_4xx',
    'notification first page omits the cursor': () =>
      firstNotificationCall.url === 'http://backend:8080/api/v1/consumers/me/notifications?size=2',
    'notification next page forwards the opaque cursor unchanged': () =>
      secondNotificationCall.url === 'http://backend:8080/api/v1/consumers/me/notifications?size=2&cursor=opaque_cursor_1',
    'notification result exposes counts but not cursor or item identifiers': () =>
      notificationResult.firstStatus === 200
      && notificationResult.nextStatus === 200
      && notificationResult.itemCount === 3
      && !combinedResult.includes('opaque_cursor_1')
      && !combinedResult.includes('notificationId'),
    'notification fixture promise fails when the first page has no next page': () =>
      throws(() => runNotificationHistory({
        client: {
          get() {
            return {
              status: 200,
              body: envelope({
                items: [notificationItem('11'), notificationItem('10')],
                hasNext: false,
                nextCursor: null,
              }),
              headers: {},
            }
          },
        },
        baseUrl: 'http://backend:8080',
        accessToken: 'access-token-value',
        pageSize: 2,
        minimumDeliveredItemsPerAccount: 3,
        tags: { phase: 'measured' },
      })),
    'notification history rejects a null deliveredAt': () =>
      notificationContractThrows([
        {
          items: [
            { ...notificationItem('11'), deliveredAt: null },
            notificationItem('10'),
          ],
          hasNext: true,
          nextCursor: 'opaque_cursor_1',
        },
        { items: [notificationItem('9')], hasNext: false, nextCursor: null },
      ]),
    'notification history rejects a non-calendar deliveredAt': () =>
      notificationContractThrows([
        {
          items: [
            { ...notificationItem('11'), deliveredAt: '2026-02-30T10:00:00+09:00' },
            notificationItem('10'),
          ],
          hasNext: true,
          nextCursor: 'opaque_cursor_1',
        },
        { items: [notificationItem('9')], hasNext: false, nextCursor: null },
      ]),
    'notification history rejects a cursor outside the OpenAPI pattern': () =>
      notificationContractThrows([
        {
          items: [notificationItem('11'), notificationItem('10')],
          hasNext: true,
          nextCursor: 'opaque.cursor.1',
        },
        { items: [notificationItem('9')], hasNext: false, nextCursor: null },
      ]),
    'notification history rejects more items than the requested page size': () =>
      notificationContractThrows([
        {
          items: [notificationItem('12'), notificationItem('11'), notificationItem('10')],
          hasNext: true,
          nextCursor: 'opaque_cursor_1',
        },
        { items: [], hasNext: false, nextCursor: null },
      ]),
    'notification history rejects an invalid action shape': () =>
      notificationContractThrows([
        {
          items: [
            {
              ...notificationItem('11'),
              action: {
                type: 'RESERVATION_DETAIL',
                resource: { type: 'PICKUP_RESERVATION', id: '9001' },
                availability: 'AVAILABLE',
                expiresAt: null,
              },
            },
            notificationItem('10'),
          ],
          hasNext: true,
          nextCursor: 'opaque_cursor_1',
        },
        { items: [notificationItem('9')], hasNext: false, nextCursor: null },
      ]),
  })
}
