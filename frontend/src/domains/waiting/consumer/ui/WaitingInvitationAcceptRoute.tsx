import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { isApiError } from '../../../../shared/api/apiError'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import {
  acceptWaitingPartyInvitation,
  consumerWaitingKeys,
} from '../api/queries'
import { toWaitingJoinError } from '../model/errors'
import type { WaitingJoinErrorView } from '../model/partyViewState'
import {
  WaitingInvitationAcceptPage,
  type WaitingJoinProgress,
} from './WaitingInvitationAcceptPage'

interface JoinAttempt {
  invitationCode: string
  idempotencyKey: string
}

/** 보호 route에서 합류 명령과 짧은 수명 상태만 소유한다. */
export function WaitingInvitationAcceptRoute() {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()
  const [progress, setProgress] = useState<WaitingJoinProgress>('idle')
  const [error, setError] = useState<WaitingJoinErrorView | null>(null)
  const attemptRef = useRef<JoinAttempt | null>(null)

  async function submit(attempt: JoinAttempt) {
    setProgress('submitting')
    setError(null)
    try {
      const snapshot = await acceptWaitingPartyInvitation(apiClient, attempt)
      queryClient.setQueryData(consumerWaitingKeys.current, snapshot)
      void queryClient.invalidateQueries({ queryKey: consumerWaitingKeys.current })
      attemptRef.current = null
      setProgress('succeeded')
    } catch (cause) {
      setProgress('idle')
      setError(toWaitingJoinError(cause))
      if (
        isApiError(cause) &&
        (cause.code === 'WAITING_005' ||
          cause.code === 'WAITING_011' ||
          cause.code === 'WAITING_015')
      ) {
        void queryClient.invalidateQueries({
          queryKey: consumerWaitingKeys.current,
        })
      }
    }
  }

  function acceptInvitation(invitationCode: string) {
    const previous = attemptRef.current
    const attempt =
      previous !== null && previous.invitationCode === invitationCode
        ? previous
        : { invitationCode, idempotencyKey: createIdempotencyKey() }
    attemptRef.current = attempt
    void submit(attempt)
  }

  function retry() {
    if (attemptRef.current !== null) void submit(attemptRef.current)
  }

  return (
    <WaitingInvitationAcceptPage
      progress={progress}
      error={error}
      onAcceptInvitation={acceptInvitation}
      onRetry={retry}
    />
  )
}
