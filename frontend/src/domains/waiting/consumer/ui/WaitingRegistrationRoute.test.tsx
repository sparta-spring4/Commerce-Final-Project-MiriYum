import { QueryClient } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConsumerAuthProvider } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { RequireConsumerAuth } from '../../../../app/shells/consumer/RequireConsumerAuth'
import { TestQueryProvider } from '../../../../test/TestQueryProvider'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedConsumer } from '../../../account/consumer/auth/test/handlers'
import { consumerWaitingKeys } from '../api/queries'
import { WaitingRegistrationRoute } from './WaitingRegistrationRoute'

const STORE_ID = '12'
const AVAILABILITY_PATH =
  `/api/v1/consumers/me/stores/${STORE_ID}/waiting-availabilities`
const PROOF_PATH =
  `/api/v1/consumers/me/stores/${STORE_ID}/waiting-location-proofs`
const CREATE_PATH = `/api/v1/consumers/me/stores/${STORE_ID}/waiting-teams`
const CURRENT_PATH = '/api/v1/consumers/me/waiting-teams/current'

const originalGeolocation = Object.getOwnPropertyDescriptor(
  window.navigator,
  'geolocation',
)

afterEach(() => {
  vi.restoreAllMocks()
  if (originalGeolocation === undefined) {
    Reflect.deleteProperty(window.navigator, 'geolocation')
  } else {
    Object.defineProperty(window.navigator, 'geolocation', originalGeolocation)
  }
})

function waitingSnapshot(queueSequence: number, teamsAhead: number) {
  return {
    waitingTeamId: '91',
    storeId: STORE_ID,
    businessDate: '2026-08-20',
    status: 'WAITING' as const,
    queueSequence,
    teamsAhead,
    partySize: 2,
    createdAt: '2026-08-20T01:02:04Z',
    calledAt: null,
    arrivalDeadline: null,
    arrivedAt: null,
    cancelledAt: null,
    version: 0,
    memberships: [
      {
        membershipId: '101',
        role: 'REPRESENTATIVE' as const,
        joinedAt: '2026-08-20T01:02:04Z',
        self: true,
      },
    ],
  }
}

function installMeasuredPosition() {
  const getCurrentPosition = vi.fn((success: PositionCallback) => {
    success({
      coords: {
        latitude: 37.5665,
        longitude: 126.978,
        accuracy: 18,
        altitude: null,
        altitudeAccuracy: null,
        heading: null,
        speed: null,
        toJSON: () => ({}),
      },
      timestamp: Date.parse('2026-08-20T01:02:03.456Z'),
      toJSON: () => ({}),
    })
  })
  Object.defineProperty(window.navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition,
    } satisfies Partial<Geolocation>,
  })
  return getCurrentPosition
}

function installPositionFailure(code: 1 | 2 | 3) {
  Object.defineProperty(window.navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition(
        _success: PositionCallback,
        failure: PositionErrorCallback,
      ) {
        failure({
          code,
          message: 'location failed',
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        })
      },
    } satisfies Partial<Geolocation>,
  })
}

function proofSnapshot(
  resultCategory:
    | 'VERIFIED'
    | 'OUTSIDE_RADIUS'
    | 'ACCURACY_INSUFFICIENT'
    | 'PERMISSION_DENIED'
    | 'MEASUREMENT_STALE'
    | 'POSITION_UNAVAILABLE'
    | 'MANIPULATION_SUSPECTED',
  proofSessionId = '550e8400-e29b-41d4-a716-446655440000',
) {
  return {
    proofSessionId,
    resultCategory,
    accuracyCategory:
      resultCategory === 'ACCURACY_INSUFFICIENT'
        ? ('INSUFFICIENT' as const)
        : resultCategory === 'VERIFIED'
          ? ('ACCEPTABLE' as const)
          : ('NOT_APPLICABLE' as const),
    policyVersion: 'WAITING_LOCATION_V1' as const,
    storeCoordinateVersion: 3,
    issuedAt: '2026-08-20T01:02:03Z',
    judgedAt: '2026-08-20T01:02:03Z',
    expiresAt: '2026-08-20T01:04:03Z',
  }
}

function acceptingAvailability() {
  return http.get(AVAILABILITY_PATH, () =>
    successResponse({
      storeId: STORE_ID,
      accepting: true,
      businessDate: '2026-08-20',
    }),
  )
}

function renderRoute(onReady?: (queryClient: QueryClient) => void) {
  return render(
    <TestQueryProvider onReady={onReady}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[`/stores/${STORE_ID}/waiting`]}>
          <Routes>
            <Route element={<RequireConsumerAuth />}>
              <Route
                path="/stores/:storeId/waiting"
                element={<WaitingRegistrationRoute />}
              />
            </Route>
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )
}

function StoreSwitch() {
  const navigate = useNavigate()
  return <button onClick={() => navigate('/stores/13/waiting')}>다른 매장</button>
}

describe('소비자 웨이팅 등록 API 연결', () => {
  it('측정 위치가 VERIFIED이면 등록하고 현재 웨이팅을 다시 조회한 snapshot을 표시한다', async () => {
    installMeasuredPosition()
    const initialHref = window.location.href
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const consoleLog = vi.spyOn(console, 'log')
    const consoleError = vi.spyOn(console, 'error')
    let proofBody: unknown = null
    let createBody: unknown = null
    let idempotencyKey: string | null = null
    let currentReads = 0
    let queryClient: QueryClient | undefined

    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () =>
        successResponse({
          storeId: STORE_ID,
          accepting: true,
          businessDate: '2026-08-20',
        }),
      ),
      http.post(PROOF_PATH, async ({ request }) => {
        proofBody = await request.json()
        return successResponse({
          proofSessionId: '550e8400-e29b-41d4-a716-446655440000',
          resultCategory: 'VERIFIED',
          accuracyCategory: 'ACCEPTABLE',
          policyVersion: 'WAITING_LOCATION_V1',
          storeCoordinateVersion: 3,
          issuedAt: '2026-08-20T01:02:03Z',
          judgedAt: '2026-08-20T01:02:03Z',
          expiresAt: '2026-08-20T01:04:03Z',
        })
      }),
      http.post(CREATE_PATH, async ({ request }) => {
        createBody = await request.json()
        idempotencyKey = request.headers.get('Idempotency-Key')
        return successResponse(waitingSnapshot(7, 3))
      }),
      http.get(CURRENT_PATH, () => {
        currentReads += 1
        return successResponse(waitingSnapshot(9, 4))
      }),
    )

    renderRoute((client) => {
      queryClient = client
    })

    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )

    expect(await screen.findByText('9번')).toBeInTheDocument()
    expect(screen.getByText('4팀')).toBeInTheDocument()
    expect(proofBody).toEqual({
      measurementStatus: 'MEASURED',
      latitude: 37.5665,
      longitude: 126.978,
      accuracyMeters: 18,
      measuredAt: '2026-08-20T01:02:03.456Z',
      integrityStatus: 'CLEAR',
    })
    expect(createBody).toEqual({
      businessDate: '2026-08-20',
      partySize: 2,
      locationProofSessionId: '550e8400-e29b-41d4-a716-446655440000',
    })
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(currentReads).toBe(1)
    await waitFor(() =>
      expect(queryClient?.getQueryData(consumerWaitingKeys.current)).toEqual(
        waitingSnapshot(9, 4),
      ),
    )
    const cachedMutationVariables = queryClient
      ?.getMutationCache()
      .getAll()
      .map((mutation) => mutation.state.variables)
    expect(JSON.stringify(cachedMutationVariables)).not.toContain('37.5665')
    expect(JSON.stringify(cachedMutationVariables)).not.toContain('126.978')
    expect(storageWrite).not.toHaveBeenCalled()
    expect(window.location.href).toBe(initialHref)
    expect(consoleLog).not.toHaveBeenCalled()
    expect(consoleError).not.toHaveBeenCalled()
  })

  it('accepting=false이면 등록 폼과 위치 요청을 시작하지 않는다', async () => {
    const getCurrentPosition = vi.fn()
    Object.defineProperty(window.navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition } satisfies Partial<Geolocation>,
    })
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () =>
        successResponse({
          storeId: STORE_ID,
          accepting: false,
          businessDate: null,
        }),
      ),
    )

    renderRoute()

    expect(
      await screen.findByText('지금은 웨이팅을 받지 않습니다.'),
    ).toBeInTheDocument()
    expect(getCurrentPosition).not.toHaveBeenCalled()
    expect(
      screen.queryByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    ).not.toBeInTheDocument()
  })

  it('재진입 시 캐시된 접수 가능 응답을 새 availability 조회 전에는 사용하지 않는다', async () => {
    const getCurrentPosition = vi.fn()
    Object.defineProperty(window.navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition } satisfies Partial<Geolocation>,
    })
    let releaseAvailability: (() => void) | undefined
    const availabilityGate = new Promise<void>((resolve) => {
      releaseAvailability = resolve
    })
    let availabilityReads = 0
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, async () => {
        availabilityReads += 1
        await availabilityGate
        return successResponse({
          storeId: STORE_ID,
          accepting: false,
          businessDate: null,
        })
      }),
    )

    renderRoute((client) => {
      client.setQueryData(consumerWaitingKeys.availability(STORE_ID), {
        storeId: STORE_ID,
        accepting: true,
        businessDate: '2026-08-19',
      })
    })

    await waitFor(() => expect(availabilityReads).toBe(1))
    expect(
      screen.queryByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    ).not.toBeInTheDocument()
    expect(getCurrentPosition).not.toHaveBeenCalled()

    releaseAvailability?.()
    expect(
      await screen.findByText('지금은 웨이팅을 받지 않습니다.'),
    ).toBeInTheDocument()
  })

  it('availability 오류는 접수 종료로 위장하지 않고 재시도 상태를 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () =>
        errorResponse(503, 'COMMON_012', '일시적인 오류입니다.'),
      ),
    )

    renderRoute()

    expect(
      await screen.findByText('웨이팅 접수 가능 여부를 확인하지 못했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('지금은 웨이팅을 받지 않습니다.'),
    ).not.toBeInTheDocument()
  })

  it('accepting=true인데 businessDate가 없으면 위치를 요청하지 않는다', async () => {
    const getCurrentPosition = vi.fn()
    Object.defineProperty(window.navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition } satisfies Partial<Geolocation>,
    })
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () =>
        successResponse({
          storeId: STORE_ID,
          accepting: true,
          businessDate: null,
        }),
      ),
    )

    renderRoute()

    expect(
      await screen.findByText('웨이팅 접수 가능 여부를 확인하지 못했습니다.'),
    ).toBeInTheDocument()
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })

  it.each([
    [1, 'PERMISSION_DENIED', '위치 권한이 거부되었습니다.'],
    [2, 'POSITION_UNAVAILABLE', '현재 위치를 확인하지 못했습니다.'],
  ] as const)(
    '브라우저 위치 실패 %s를 %s 판정 요청으로 보내고 등록하지 않는다',
    async (errorCode, measurementStatus, guidance) => {
      installPositionFailure(errorCode)
      let proofBody: unknown = null
      let createCalls = 0
      server.use(
        authenticatedConsumer(),
        acceptingAvailability(),
        http.post(PROOF_PATH, async ({ request }) => {
          proofBody = await request.json()
          return successResponse(proofSnapshot(measurementStatus))
        }),
        http.post(CREATE_PATH, () => {
          createCalls += 1
          return successResponse(waitingSnapshot(7, 3))
        }),
      )

      renderRoute()
      fireEvent.click(
        await screen.findByRole('button', {
          name: '현재 위치 확인 후 웨이팅 등록',
        }),
      )

      expect(await screen.findByText(guidance)).toBeInTheDocument()
      expect(proofBody).toEqual({ measurementStatus, integrityStatus: 'CLEAR' })
      expect(createCalls).toBe(0)
    },
  )

  it('위치 증빙 API 오류는 성공으로 처리하지 않고 재시도를 안내한다', async () => {
    installMeasuredPosition()
    let createCalls = 0
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.post(PROOF_PATH, () =>
        errorResponse(503, 'COMMON_012', '위치 판정을 사용할 수 없습니다.'),
      ),
      http.post(CREATE_PATH, () => {
        createCalls += 1
        return successResponse(waitingSnapshot(7, 3))
      }),
    )

    renderRoute()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )

    expect(
      await screen.findByText('위치 판정을 사용할 수 없습니다.'),
    ).toBeInTheDocument()
    expect(createCalls).toBe(0)
  })

  it.each([
    ['OUTSIDE_RADIUS', '매장에서 3km 넘게 떨어져 있습니다.'],
    ['ACCURACY_INSUFFICIENT', '위치 정확도가 부족합니다.'],
    ['MEASUREMENT_STALE', '확인한 위치가 오래되었습니다.'],
    ['MANIPULATION_SUSPECTED', '현재 위치를 확인할 수 없습니다.'],
  ] as const)(
    '%s 판정은 웨이팅을 만들지 않고 해당 복구 안내를 표시한다',
    async (category, guidance) => {
      installMeasuredPosition()
      let createCalls = 0
      server.use(
        authenticatedConsumer(),
        acceptingAvailability(),
        http.post(PROOF_PATH, () => successResponse(proofSnapshot(category))),
        http.post(CREATE_PATH, () => {
          createCalls += 1
          return successResponse(waitingSnapshot(7, 3))
        }),
      )

      renderRoute()
      fireEvent.click(
        await screen.findByRole('button', {
          name: '현재 위치 확인 후 웨이팅 등록',
        }),
      )

      expect(await screen.findByText(guidance)).toBeInTheDocument()
      expect(createCalls).toBe(0)
    },
  )

  it('등록 API의 partySize 필드 오류를 인원 입력 가까이에 표시한다', async () => {
    installMeasuredPosition()
    server.use(
      authenticatedConsumer(),
      http.get(AVAILABILITY_PATH, () =>
        successResponse({
          storeId: STORE_ID,
          accepting: true,
          businessDate: '2026-08-20',
        }),
      ),
      http.post(PROOF_PATH, () =>
        successResponse({
          proofSessionId: '550e8400-e29b-41d4-a716-446655440000',
          resultCategory: 'VERIFIED',
          accuracyCategory: 'ACCEPTABLE',
          policyVersion: 'WAITING_LOCATION_V1',
          storeCoordinateVersion: 3,
          issuedAt: '2026-08-20T01:02:03Z',
          judgedAt: '2026-08-20T01:02:03Z',
          expiresAt: '2026-08-20T01:04:03Z',
        }),
      ),
      http.post(CREATE_PATH, () =>
        errorResponse(400, 'COMMON_001', '입력값을 확인해 주세요.', [
          { field: 'partySize', reason: '방문 인원은 허용 범위 안이어야 합니다.' },
        ]),
      ),
    )

    renderRoute()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )

    expect(
      await screen.findByText('방문 인원은 허용 범위 안이어야 합니다.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('방문 인원')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
  })

  it('동일 입력의 등록 재시도는 위치를 다시 측정하지 않고 같은 키와 본문을 사용한다', async () => {
    const getCurrentPosition = installMeasuredPosition()
    let proofCalls = 0
    const keys: Array<string | null> = []
    const bodies: unknown[] = []
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.post(PROOF_PATH, () => {
        proofCalls += 1
        return successResponse(proofSnapshot('VERIFIED'))
      }),
      http.post(CREATE_PATH, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        bodies.push(await request.json())
        return keys.length === 1
          ? errorResponse(503, 'COMMON_012', '일시적인 오류입니다.')
          : successResponse(waitingSnapshot(7, 3))
      }),
      http.get(CURRENT_PATH, () => successResponse(waitingSnapshot(7, 3))),
    )

    renderRoute()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('7번')).toBeInTheDocument()
    expect(getCurrentPosition).toHaveBeenCalledTimes(1)
    expect(proofCalls).toBe(1)
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(bodies[1]).toEqual(bodies[0])
  })

  it('WAITING_013이면 새 위치 측정과 새 증빙·멱등 키로 다시 시작한다', async () => {
    const getCurrentPosition = installMeasuredPosition()
    let proofCalls = 0
    const keys: Array<string | null> = []
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.post(PROOF_PATH, () => {
        proofCalls += 1
        return successResponse(
          proofSnapshot(
            'VERIFIED',
            proofCalls === 1
              ? '550e8400-e29b-41d4-a716-446655440000'
              : '550e8400-e29b-41d4-a716-446655440001',
          ),
        )
      }),
      http.post(CREATE_PATH, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return keys.length === 1
          ? errorResponse(409, 'WAITING_013', '사용할 수 없는 위치 증명입니다.')
          : successResponse(waitingSnapshot(8, 2))
      }),
      http.get(CURRENT_PATH, () => successResponse(waitingSnapshot(8, 2))),
    )

    renderRoute()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )
    fireEvent.click(
      await screen.findByRole('button', { name: '현재 위치 다시 확인' }),
    )

    expect(await screen.findByText('8번')).toBeInTheDocument()
    expect(getCurrentPosition).toHaveBeenCalledTimes(2)
    expect(proofCalls).toBe(2)
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('입력을 바꾼 새 등록은 위치를 다시 측정하고 새 멱등 키를 발급한다', async () => {
    const getCurrentPosition = installMeasuredPosition()
    const keys: Array<string | null> = []
    let proofCalls = 0
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.post(PROOF_PATH, () => {
        proofCalls += 1
        return successResponse(
          proofSnapshot(
            'VERIFIED',
            `550e8400-e29b-41d4-a716-44665544000${proofCalls - 1}`,
          ),
        )
      }),
      http.post(CREATE_PATH, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return keys.length === 1
          ? errorResponse(503, 'COMMON_012', '일시적인 오류입니다.')
          : successResponse({ ...waitingSnapshot(10, 5), partySize: 3 })
      }),
      http.get(CURRENT_PATH, () =>
        successResponse({ ...waitingSnapshot(10, 5), partySize: 3 }),
      ),
    )

    renderRoute()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )
    await screen.findByRole('button', { name: '다시 시도' })
    fireEvent.click(screen.getByRole('button', { name: '인원 늘리기' }))
    fireEvent.click(
      screen.getByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )

    expect(await screen.findByText('10번')).toBeInTheDocument()
    expect(getCurrentPosition).toHaveBeenCalledTimes(2)
    expect(proofCalls).toBe(2)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('연속 제출해도 위치 판정과 웨이팅 팀을 한 번만 만든다', async () => {
    installMeasuredPosition()
    let releaseProof: (() => void) | undefined
    const proofGate = new Promise<void>((resolve) => {
      releaseProof = resolve
    })
    let proofCalls = 0
    let createCalls = 0
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.post(PROOF_PATH, async () => {
        proofCalls += 1
        await proofGate
        return successResponse(proofSnapshot('VERIFIED'))
      }),
      http.post(CREATE_PATH, () => {
        createCalls += 1
        return successResponse(waitingSnapshot(11, 6))
      }),
      http.get(CURRENT_PATH, () => successResponse(waitingSnapshot(11, 6))),
    )

    renderRoute()
    const form = await screen.findByRole('form', { name: '웨이팅 등록' })
    fireEvent.submit(form)
    fireEvent.submit(form)
    await waitFor(() => expect(proofCalls).toBe(1))
    releaseProof?.()

    expect(await screen.findByText('11번')).toBeInTheDocument()
    expect(proofCalls).toBe(1)
    expect(createCalls).toBe(1)
  })

  it('등록 중 화면을 떠나면 늦은 응답이 현재 웨이팅 캐시를 다시 채우지 않는다', async () => {
    installMeasuredPosition()
    let releaseCreate: (() => void) | undefined
    const createGate = new Promise<void>((resolve) => {
      releaseCreate = resolve
    })
    let createCalls = 0
    let currentReads = 0
    let queryClient: QueryClient | undefined
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.post(PROOF_PATH, () => successResponse(proofSnapshot('VERIFIED'))),
      http.post(CREATE_PATH, async () => {
        createCalls += 1
        await createGate
        return successResponse(waitingSnapshot(14, 8))
      }),
      http.get(CURRENT_PATH, () => {
        currentReads += 1
        return successResponse(waitingSnapshot(14, 8))
      }),
    )

    const rendered = renderRoute((client) => {
      queryClient = client
    })
    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )
    await waitFor(() => expect(createCalls).toBe(1))

    rendered.unmount()
    releaseCreate?.()
    await waitFor(() => expect(queryClient?.isMutating()).toBe(0))

    expect(currentReads).toBe(0)
    expect(
      queryClient?.getQueryData(consumerWaitingKeys.current),
    ).toBeUndefined()
  })

  it('등록 중 다른 storeId로 이동하면 이전 매장의 증빙으로 등록하지 않는다', async () => {
    installMeasuredPosition()
    let releaseProof: (() => void) | undefined
    const proofGate = new Promise<void>((resolve) => {
      releaseProof = resolve
    })
    let oldStoreCreates = 0
    server.use(
      authenticatedConsumer(),
      acceptingAvailability(),
      http.get('/api/v1/consumers/me/stores/13/waiting-availabilities', () =>
        successResponse({
          storeId: '13',
          accepting: true,
          businessDate: '2026-08-21',
        }),
      ),
      http.post(PROOF_PATH, async () => {
        await proofGate
        return successResponse(proofSnapshot('VERIFIED'))
      }),
      http.post(CREATE_PATH, () => {
        oldStoreCreates += 1
        return successResponse(waitingSnapshot(15, 9))
      }),
    )

    render(
      <TestQueryProvider>
        <ConsumerAuthProvider>
          <MemoryRouter initialEntries={[`/stores/${STORE_ID}/waiting`]}>
            <StoreSwitch />
            <Routes>
              <Route element={<RequireConsumerAuth />}>
                <Route
                  path="/stores/:storeId/waiting"
                  element={<WaitingRegistrationRoute />}
                />
              </Route>
            </Routes>
          </MemoryRouter>
        </ConsumerAuthProvider>
      </TestQueryProvider>,
    )

    fireEvent.click(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '다른 매장' }))
    releaseProof?.()

    expect(
      await screen.findByRole('button', {
        name: '현재 위치 확인 후 웨이팅 등록',
      }),
    ).toBeInTheDocument()
    expect(oldStoreCreates).toBe(0)
  })
})
