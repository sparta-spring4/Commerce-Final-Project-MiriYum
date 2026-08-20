import { CONSUMER_PATHS } from '../../../app/routes/paths/consumerPaths'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'

import type { ApiClient } from '../../../shared/api/client'
import { hasErrorCode } from '../../../shared/api/apiError'
import { toAsyncState } from '../../../shared/ui/asyncState'
import {
  readNotificationHistoryPage,
  type NotificationHistoryItem,
} from './notificationHistoryApi'
import { getNotificationPurposeLabel } from './notificationPurposeLabel'
import type {
  NotificationEventConnectionState,
  NotificationEventStreamClient,
} from './notificationEventStream'

const NOTIFICATION_HISTORY_QUERY_ROOT = ['consumer', 'notification-history'] as const

type NotificationHistoryPageProps = {
  apiClient: ApiClient
  eventStream: NotificationEventStreamClient
  sessionKey: number
}

function formatDeliveredAt(deliveredAt: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(deliveredAt))
}

type DetailActionPresentation =
  | { kind: 'link'; label: string; to: string }
  | { kind: 'disabled'; label: string }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function resolveDetailAction(
  action: unknown,
): DetailActionPresentation | null {
  if (!isRecord(action) || !isRecord(action.resource)) {
    return null
  }

  const resourceId = action.resource.id
  if (typeof resourceId !== 'string' || resourceId.trim().length === 0) {
    return null
  }

  let label: string
  let to: string
  if (
    action.type === 'RESERVATION_DETAIL' &&
    action.resource.type === 'RESERVATION'
  ) {
    label = '예약 상세 보기'
    to = CONSUMER_PATHS.reservationDetail.replace(
      ':reservationId',
      encodeURIComponent(resourceId),
    )
  } else if (
    action.type === 'PICKUP_RESERVATION_DETAIL' &&
    action.resource.type === 'PICKUP_RESERVATION'
  ) {
    label = '픽업 상세 보기'
    to = CONSUMER_PATHS.pickupDetail.replace(
      ':pickupReservationId',
      encodeURIComponent(resourceId),
    )
  } else {
    return null
  }

  if (action.availability === 'AVAILABLE') {
    return { kind: 'link', label, to }
  }

  if (
    action.availability === 'EXPIRED' ||
    action.availability === 'SUPERSEDED' ||
    action.availability === 'UNAVAILABLE'
  ) {
    return { kind: 'disabled', label }
  }

  return null
}

function NotificationItem({ item }: { item: NotificationHistoryItem }) {
  const detailAction = resolveDetailAction(item.action)

  return (
    <li>
      <article>
        <p>{getNotificationPurposeLabel(item.purpose)}</p>
        <h2>{item.title}</h2>
        <time dateTime={item.deliveredAt}>
          {formatDeliveredAt(item.deliveredAt)}
        </time>
        {detailAction?.kind === 'link' ? (
          <Link to={detailAction.to}>{detailAction.label}</Link>
        ) : null}
        {detailAction?.kind === 'disabled' ? (
          <button type="button" disabled>
            {detailAction.label}
          </button>
        ) : null}
      </article>
    </li>
  )
}

function HistoryError({
  error,
  onRetry,
  onReset,
}: {
  error: unknown
  onRetry: () => void
  onReset: () => void
}) {
  if (hasErrorCode(error, 'NOTIFICATION_001')) {
    return (
      <section role="alert">
        <p>이전 알림 목록 위치를 사용할 수 없습니다.</p>
        <button type="button" onClick={onReset}>
          최신 알림부터 다시 보기
        </button>
      </section>
    )
  }

  const presentation = toAsyncState(error)

  if (presentation.state === 'forbidden') {
    if (presentation.action === 'signIn') {
      return (
        <section role="alert">
          <p>로그인이 필요합니다.</p>
          <Link to={CONSUMER_PATHS.signIn}>로그인하기</Link>
        </section>
      )
    }

    return <p role="alert">현재 계정으로 알림 이력을 볼 수 없습니다.</p>
  }

  if (
    presentation.state === 'awaitingRecovery' &&
    presentation.action === 'recheck'
  ) {
    return (
      <section role="alert">
        <p>알림 이력을 잠시 불러올 수 없습니다.</p>
        <button type="button" onClick={onRetry}>
          다시 확인
        </button>
      </section>
    )
  }

  if (presentation.state === 'indeterminate') {
    return (
      <section role="alert">
        <p>알림 이력 조회 결과를 확인할 수 없습니다.</p>
        <button type="button" onClick={onRetry}>
          다시 확인
        </button>
      </section>
    )
  }

  if (presentation.state === 'awaitingRecovery') {
    return <p role="alert">알림 이력을 복구하는 중입니다.</p>
  }

  return (
    <section role="alert">
      <p>알림 이력을 불러오지 못했습니다.</p>
      <button type="button" onClick={onRetry}>
        다시 시도
      </button>
    </section>
  )
}

export function NotificationHistoryPage({
  apiClient,
  eventStream,
  sessionKey,
}: NotificationHistoryPageProps) {
  const queryClient = useQueryClient()
  const [eventConnectionState, setEventConnectionState] =
    useState<NotificationEventConnectionState | null>(null)
  const queryKey = useMemo(
    () => [...NOTIFICATION_HISTORY_QUERY_ROOT, sessionKey] as const,
    [sessionKey],
  )
  const history = useInfiniteQuery({
    queryKey,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      readNotificationHistoryPage(apiClient, {
        cursor: pageParam,
        signal,
      }),
    getNextPageParam: (lastPage) =>
      lastPage.hasNext && lastPage.nextCursor !== null
        ? lastPage.nextCursor
        : undefined,
  })

  useEffect(() => {
    const controller = new AbortController()
    let refreshRequested = false
    let refreshRunning = false

    const refreshHistory = async () => {
      if (refreshRunning) {
        return
      }

      refreshRunning = true
      try {
        while (refreshRequested && !controller.signal.aborted) {
          const queryState = queryClient.getQueryState(queryKey)
          if (
            queryState?.fetchStatus === 'fetching' &&
            queryState.data === undefined
          ) {
            await queryClient.cancelQueries({
              queryKey,
              exact: true,
            })
          }

          if (controller.signal.aborted) {
            return
          }

          refreshRequested = false
          await queryClient.invalidateQueries({
            queryKey,
            exact: true,
          })
        }
      } finally {
        refreshRunning = false
      }
    }

    setEventConnectionState(null)
    void eventStream.subscribe({
      signal: controller.signal,
      onChanged: () => {
        if (controller.signal.aborted) {
          return
        }

        refreshRequested = true
        void refreshHistory()
      },
      onConnectionStateChange: setEventConnectionState,
    })

    return () => {
      refreshRequested = false
      controller.abort()
    }
  }, [eventStream, queryClient, queryKey])

  useEffect(
    () => () => {
      queryClient.removeQueries({
        queryKey,
        exact: true,
      })
    },
    [queryClient, queryKey],
  )

  const resetToNewest = () => {
    void queryClient.resetQueries({
      queryKey,
      exact: true,
    })
  }

  const items = history.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <main>
      <h1>알림 이력</h1>

      {eventConnectionState === 'reconnecting' ? (
        <p
          role="status"
          aria-label="실시간 알림 연결을 복구하는 중입니다."
        >
          실시간 알림 연결을 복구하는 중입니다.
        </p>
      ) : null}

      {eventConnectionState === 'unavailable' ? (
        <p role="status">실시간 알림 연결을 사용할 수 없습니다.</p>
      ) : null}

      {history.isPending ? <p role="status">알림 이력을 불러오는 중입니다.</p> : null}

      {history.isError && !history.isFetchNextPageError ? (
        <HistoryError
          error={history.error}
          onRetry={() => void history.refetch()}
          onReset={resetToNewest}
        />
      ) : null}

      {history.isSuccess && items.length === 0 ? (
        <p>아직 받은 알림이 없습니다.</p>
      ) : null}

      {items.length > 0 ? (
        <ul>
          {items.map((item) => (
            <NotificationItem key={item.notificationId} item={item} />
          ))}
        </ul>
      ) : null}

      {history.isFetchNextPageError ? (
        <HistoryError
          error={history.error}
          onRetry={() => void history.fetchNextPage()}
          onReset={resetToNewest}
        />
      ) : null}

      {history.hasNextPage && !history.isFetchNextPageError ? (
        <button
          type="button"
          disabled={history.isFetchingNextPage}
          onClick={() => void history.fetchNextPage()}
        >
          {history.isFetchingNextPage ? '불러오는 중' : '더 보기'}
        </button>
      ) : null}
    </main>
  )
}
