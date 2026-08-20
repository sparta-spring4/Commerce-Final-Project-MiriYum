import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { PUBLIC_PATHS } from '../../../../app/routes/paths/publicPaths'
import { useConsumerAuth } from '../../../../app/shells/consumer/ConsumerAuthProvider'
import { isApiError } from '../../../../shared/api/apiError'
import { useIdempotentAttempt } from '../../../../shared/api/useIdempotentAttempt'
import { Alert, EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import {
  cancelConsumerWaitingTeam,
  consumerWaitingKeys,
  useCurrentConsumerWaiting,
  type ConsumerWaitingSnapshot,
} from '../api/queries'
import {
  toWaitingCancelError,
  type WaitingCancelErrorView,
} from '../model/currentWaitingView'
import { CurrentWaitingPage, type WaitingCancelPhase } from './CurrentWaitingPage'
import { WaitingPartyPanelContainer } from './WaitingPartyPanelContainer'
import './currentWaiting.css'

/**
 * 현재 웨이팅 상세 route.
 *
 * 중앙 snapshot 조회를 소유하고, 일행 구성은 #482의 패널 컨테이너에 그대로
 * 넘긴다. 계정이 바뀌면 취소 시도 상태를 이어받지 않도록 `sessionKey`로
 * 컨트롤러를 새로 만든다.
 */
export function CurrentWaitingRoute() {
  const { sessionKey } = useConsumerAuth()
  return <CurrentWaitingController key={sessionKey} />
}

function CurrentWaitingController() {
  const { apiClient } = useConsumerAuth()
  const queryClient = useQueryClient()
  const current = useCurrentConsumerWaiting()
  const [phase, setPhase] = useState<WaitingCancelPhase>('idle')
  const [cancelError, setCancelError] = useState<WaitingCancelErrorView | null>(
    null,
  )
  const [cancelled, setCancelled] = useState<ConsumerWaitingSnapshot | null>(
    null,
  )
  const active = useRef(true)

  /*
   * mount마다 다시 켠다. cleanup만 두면 StrictMode의 mount→unmount→mount에서
   * 두 번째 mount가 꺼진 ref를 물려받아 이후 모든 응답이 조용히 버려진다.
   */
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])

  const snapshot = current.data ?? null

  /*
   * `expectedVersion`은 취소 요청 본문이라 요청 지문의 일부다. 팀이나 version이
   * 바뀌면 새 명령이므로 새 키를 쓰고, 그대로면 같은 키로 결과를 수렴시킨다.
   */
  const attempt = useIdempotentAttempt(
    snapshot === null ? '' : `${snapshot.waitingTeamId}:${snapshot.version}`,
  )

  function refresh() {
    setCancelError(null)
    void current.refetch()
  }

  async function confirmCancel() {
    if (snapshot === null) {
      return
    }

    const idempotencyKey = attempt.begin()
    if (idempotencyKey === null) {
      // 결과 불명 뒤 version이 바뀌었다. 같은 키를 다른 지문으로 보내면 두 번 처리된다.
      setPhase('idle')
      setCancelError({ code: 'OUTCOME_UNKNOWN' })
      return
    }

    setPhase('submitting')
    setCancelError(null)
    try {
      const result = await cancelConsumerWaitingTeam(apiClient, {
        waitingTeamId: snapshot.waitingTeamId,
        expectedVersion: snapshot.version,
        idempotencyKey,
      })
      if (!active.current) {
        return
      }
      attempt.settle(null)
      setCancelled(result)
      setPhase('idle')
      /*
       * 취소되면 활성 membership이 사라져 중앙 조회가 다시 404로 떨어진다.
       * 캐시를 비우고 재조회를 걸어 마이페이지 카드까지 같은 결론에 수렴시킨다.
       */
      queryClient.setQueryData(consumerWaitingKeys.current, null)
      void queryClient.invalidateQueries({
        queryKey: consumerWaitingKeys.current,
      })
    } catch (cause) {
      if (!active.current) {
        return
      }
      attempt.settle(cause)
      setPhase('idle')
      setCancelError(toWaitingCancelError(cause))
      if (
        isApiError(cause) &&
        (cause.code === 'WAITING_003' ||
          cause.code === 'WAITING_005' ||
          cause.code === 'WAITING_006')
      ) {
        void queryClient.invalidateQueries({
          queryKey: consumerWaitingKeys.current,
        })
      }
    }
  }

  /* 취소 결과를 먼저 본다. 취소되면 중앙 조회는 다시 빈 상태가 되므로, 그대로
     두면 사용자가 방금 한 조작의 결과가 "웨이팅 없음"으로만 보인다. */
  if (cancelled !== null) {
    return (
      <div className="mi-container mi-container--narrow waiting-current">
        <header className="mi-page-head">
          <h1 className="mi-page-head__title">웨이팅 취소</h1>
        </header>
        <Alert tone="info" title="웨이팅을 취소했습니다.">
          <p>순번이 사라졌습니다. 다시 이용하려면 매장에서 새로 등록해 주세요.</p>
        </Alert>
        <p className="waiting-current__store-link">
          <Link to={`/stores/${cancelled.storeId}`}>
            매장 정보 보기
            <Icon name="arrowRight" className="mi-icon--sm" />
          </Link>
        </p>
        <p className="waiting-current__store-link">
          <Link to={CONSUMER_PATHS.myPage}>
            마이페이지로 돌아가기
            <Icon name="arrowRight" className="mi-icon--sm" />
          </Link>
        </p>
      </div>
    )
  }

  if (current.isPending) {
    return (
      <div className="mi-container mi-container--narrow waiting-current">
        <Loading label="현재 웨이팅을 불러오는 중입니다." />
      </div>
    )
  }

  if (current.isError) {
    return (
      <div className="mi-container mi-container--narrow waiting-current">
        <ErrorState
          error={current.error}
          message="현재 웨이팅을 불러오지 못했습니다."
          onRetry={() => void current.refetch()}
        />
        <p className="waiting-current__store-link">
          <Link to={CONSUMER_PATHS.myPage}>
            마이페이지로 돌아가기
            <Icon name="arrowRight" className="mi-icon--sm" />
          </Link>
        </p>
      </div>
    )
  }

  if (snapshot === null) {
    return (
      <div className="mi-container mi-container--narrow waiting-current">
        <header className="mi-page-head">
          <h1 className="mi-page-head__title">현재 웨이팅</h1>
        </header>
        <EmptyState
          title="현재 웨이팅이 없습니다."
          description="매장 상세에서 웨이팅을 등록하면 순번과 앞 팀 수를 이곳에서 확인할 수 있습니다."
          action={
            <Link className="mi-button mi-button--primary" to={PUBLIC_PATHS.stores}>
              매장 찾기
            </Link>
          }
        />
      </div>
    )
  }

  return (
    <CurrentWaitingPage
      snapshot={snapshot}
      fetchedAt={current.dataUpdatedAt}
      refreshing={current.isFetching}
      cancelPhase={phase}
      cancelError={cancelError}
      partyPanel={<WaitingPartyPanelContainer snapshot={snapshot} />}
      onRefresh={refresh}
      onRequestCancel={() => setPhase('confirming')}
      onConfirmCancel={() => void confirmCancel()}
      onDismissCancel={() => setPhase('idle')}
    />
  )
}
