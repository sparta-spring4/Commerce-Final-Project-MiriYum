import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'

import type { ApiClient } from '../../shared/api/client'
import {
  hasErrorCode,
  isApiContractError,
  isApiError,
  isNetworkError,
} from '../../shared/api/apiError'
import { CommonErrorCode } from '../../shared/api/envelope'
import {
  readNotificationHistoryPage,
  type NotificationHistoryItem,
} from './notificationHistoryApi'
import { getNotificationPurposeLabel } from './notificationPurposeLabel'

const NOTIFICATION_HISTORY_QUERY_KEY = ['consumer', 'notification-history']

type NotificationHistoryPageProps = {
  apiClient: ApiClient
}

function formatDeliveredAt(deliveredAt: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(deliveredAt))
}

function NotificationItem({ item }: { item: NotificationHistoryItem }) {
  return (
    <li>
      <article>
        <p>{getNotificationPurposeLabel(item.purpose)}</p>
        <h2>{item.title}</h2>
        <time dateTime={item.deliveredAt}>
          {formatDeliveredAt(item.deliveredAt)}
        </time>
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

  if (isApiError(error) && error.status === 401) {
    return <p role="alert">로그인이 필요합니다.</p>
  }

  if (isApiError(error) && error.status === 403) {
    return <p role="alert">현재 계정으로 알림 이력을 볼 수 없습니다.</p>
  }

  if (
    isApiError(error) &&
    (error.status === 503 || error.code === CommonErrorCode.SERVICE_UNAVAILABLE)
  ) {
    return (
      <section role="alert">
        <p>알림 이력을 잠시 불러올 수 없습니다.</p>
        <button type="button" onClick={onRetry}>
          다시 시도
        </button>
      </section>
    )
  }

  if (isNetworkError(error)) {
    return (
      <section role="alert">
        <p>알림 이력 조회 결과를 확인할 수 없습니다.</p>
        <button type="button" onClick={onRetry}>
          다시 확인
        </button>
      </section>
    )
  }

  if (isApiContractError(error)) {
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
}: NotificationHistoryPageProps) {
  const queryClient = useQueryClient()
  const history = useInfiniteQuery({
    queryKey: NOTIFICATION_HISTORY_QUERY_KEY,
    gcTime: 0,
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

  const resetToNewest = () => {
    void queryClient.resetQueries({
      queryKey: NOTIFICATION_HISTORY_QUERY_KEY,
      exact: true,
    })
  }

  const items = history.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <main>
      <h1>알림 이력</h1>

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
