import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { isApiError } from '../../../../shared/api/apiError'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { ErrorState } from '../../../../shared/ui/Feedback'
import {
  issueConsumerWaitingLocationProof,
  refetchCurrentConsumerWaiting,
  useConsumerWaitingAvailability,
  useCreateConsumerWaitingTeam,
  type ConsumerWaitingCreateRequest,
} from '../api/queries'
import { measureWaitingLocation } from '../model/locationMeasurement'
import type {
  WaitingRegistrationNotice,
  WaitingRegistrationProgress,
  WaitingRegistrationResult,
} from '../model/registrationView'
import { WaitingRegistrationPage } from './WaitingRegistrationPage'

interface RegistrationAttempt {
  storeId: string
  body: ConsumerWaitingCreateRequest
  idempotencyKey: string
}

export function WaitingRegistrationRoute() {
  const { storeId = '' } = useParams()
  const { sessionKey } = useConsumerAuth()

  return (
    <WaitingRegistrationController
      key={`${sessionKey}:${storeId}`}
      storeId={storeId}
    />
  )
}

function WaitingRegistrationController({ storeId }: { storeId: string }) {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()
  const availability = useConsumerWaitingAvailability(storeId)
  const createWaiting = useCreateConsumerWaitingTeam(storeId)

  const [partySize, setPartySize] = useState(2)
  const [progress, setProgress] =
    useState<WaitingRegistrationProgress>('idle')
  const [notice, setNotice] = useState<WaitingRegistrationNotice | null>(null)
  const [partySizeError, setPartySizeError] = useState<string | null>(null)
  const [result, setResult] = useState<WaitingRegistrationResult | null>(null)
  const inFlight = useRef(false)
  const attempt = useRef<RegistrationAttempt | null>(null)
  const active = useRef(true)
  const activeRequest = useRef<AbortController | null>(null)

  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
      activeRequest.current?.abort()
    }
  }, [])

  if (availability.isError) {
    return (
      <div className="mi-container mi-container--narrow">
        <ErrorState
          error={availability.error}
          message="웨이팅 접수 가능 여부를 확인하지 못했습니다."
          onRetry={() => void availability.refetch()}
        />
      </div>
    )
  }

  if (
    availability.isSuccess &&
    availability.data.accepting &&
    availability.data.businessDate === null
  ) {
    return (
      <div className="mi-container mi-container--narrow">
        <ErrorState
          error={new Error('accepting availability has no businessDate')}
          message="웨이팅 접수 가능 여부를 확인하지 못했습니다."
          onRetry={() => void availability.refetch()}
        />
      </div>
    )
  }

  const reception = availability.isPending || availability.isFetching
    ? 'checking'
    : availability.data?.accepting
      ? 'accepting'
      : 'closed'

  async function register(forceLocation: boolean) {
    if (inFlight.current) return
    const businessDate = availability.data?.businessDate
    if (
      availability.isFetching ||
      !availability.data?.accepting ||
      businessDate === null ||
      businessDate === undefined
    ) {
      return
    }

    inFlight.current = true
    const requestController = new AbortController()
    activeRequest.current = requestController
    setNotice(null)
    setPartySizeError(null)
    try {
      let currentAttempt = forceLocation ? null : attempt.current
      if (
        currentAttempt === null ||
        currentAttempt.storeId !== storeId ||
        currentAttempt.body.partySize !== partySize ||
        currentAttempt.body.businessDate !== businessDate
      ) {
        setProgress('locating')
        const measurement = await measureWaitingLocation(
          window.navigator.geolocation,
        )
        if (!active.current || requestController.signal.aborted) return
        const proof = await issueConsumerWaitingLocationProof(
          apiClient,
          storeId,
          measurement,
          requestController.signal,
        )
        if (!active.current || requestController.signal.aborted) return
        if (proof.resultCategory !== 'VERIFIED') {
          setNotice({ code: proof.resultCategory })
          setProgress('idle')
          attempt.current = null
          return
        }

        currentAttempt = {
          storeId,
          body: {
            businessDate,
            partySize,
            locationProofSessionId: proof.proofSessionId,
          },
          idempotencyKey: createIdempotencyKey(),
        }
        attempt.current = currentAttempt
      }

      setProgress('registering')
      const created = await createWaiting.mutateAsync({
        body: currentAttempt.body,
        idempotencyKey: currentAttempt.idempotencyKey,
        signal: requestController.signal,
      })
      if (!active.current || requestController.signal.aborted) return
      let snapshot = created
      try {
        snapshot =
          (await refetchCurrentConsumerWaiting(
            queryClient,
            apiClient,
            requestController.signal,
          )) ?? created
      } catch {
        // 등록 성공은 생성 응답으로 이미 확정됐다. 재조회 실패가 성공을 뒤집지 않는다.
      }
      if (!active.current || requestController.signal.aborted) return
      setResult({
        queueSequence: snapshot.queueSequence,
        teamsAhead: snapshot.teamsAhead,
        partySize: snapshot.partySize,
      })
      setProgress('succeeded')
    } catch (error) {
      if (!active.current || requestController.signal.aborted) return
      if (isApiError(error)) {
        setPartySizeError(
          error.details.find((detail) => detail.field === 'partySize')?.reason ??
            null,
        )
      }
      if (isApiError(error) && error.status === 409 && error.code === 'WAITING_013') {
        attempt.current = null
        setNotice({ code: 'LOCATION_PROOF_INVALID' })
      } else {
        setNotice({
          code: 'REQUEST_FAILED',
          message: isApiError(error) ? error.message : undefined,
        })
      }
      setProgress('idle')
    } finally {
      inFlight.current = false
      if (activeRequest.current === requestController) {
        activeRequest.current = null
      }
    }
  }

  function changePartySize(next: number) {
    if (next !== partySize) {
      attempt.current = null
      setNotice(null)
      setPartySizeError(null)
    }
    setPartySize(next)
  }

  return (
    <WaitingRegistrationPage
      storeId={storeId}
      reception={reception}
      progress={progress}
      partySize={partySize}
      partySizeError={partySizeError}
      notice={notice}
      result={result}
      onPartySizeChange={changePartySize}
      onSubmit={() => void register(false)}
      onRetryLocation={() => void register(true)}
    />
  )
}
