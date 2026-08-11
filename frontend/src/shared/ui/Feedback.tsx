import type { ReactNode } from 'react'
import { toAsyncState, type AsyncAction } from './asyncState'

/** 오류·경고·안내 배너. 색만으로 의미를 전달하지 않도록 제목 문구를 항상 둔다. */
export function Alert({
  tone,
  title,
  children,
  actions,
}: {
  tone: 'error' | 'warning' | 'info'
  title: string
  children?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div
      className={`mi-alert mi-alert--${tone}`}
      role={tone === 'error' ? 'alert' : 'status'}
    >
      <p className="mi-alert__title">{title}</p>
      {children}
      {actions && <div className="mi-alert__actions">{actions}</div>}
    </div>
  )
}

/** 로딩 표시. 스피너만 두지 않고 문구를 함께 읽히게 한다. */
export function Loading({ label = '불러오는 중입니다.' }: { label?: string }) {
  return (
    <p className="mi-loading" role="status">
      <span className="mi-spinner" aria-hidden="true" />
      {label}
    </p>
  )
}

/** 빈 결과. 오류가 아니므로 alert가 아니라 일반 영역으로 표시한다. */
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="mi-empty">
      <p className="mi-empty__title">{title}</p>
      {description && <p>{description}</p>}
      {action}
    </div>
  )
}

/**
 * 사용자가 지금 할 수 있는 다음 행동 문구.
 * asyncState의 AsyncAction과 1:1로 대응한다.
 */
const ACTION_LABEL: Record<AsyncAction, string | null> = {
  none: null,
  retry: '다시 시도',
  fixInput: '입력을 확인해 주세요.',
  signIn: '로그인하기',
  recheck: '상태 다시 확인',
}

/**
 * 오류를 공통 화면 상태로 옮겨 표시한다.
 *
 * 서버가 준 메시지를 그대로 노출하되, 상태 판정은 asyncState가 소유한다.
 * 화면마다 오류 문자열을 파싱해 분기하지 않기 위한 단일 통로다.
 */
export function ErrorState({
  error,
  message,
  onRetry,
  onSignIn,
}: {
  error: unknown
  /** 화면이 서버 code로 판정한 문구. 없으면 상태 기본 문구를 쓴다. */
  message?: string
  onRetry?: () => void
  onSignIn?: () => void
}) {
  const { state, action } = toAsyncState(error)

  const title = message ?? DEFAULT_MESSAGE[state]
  const actionLabel = ACTION_LABEL[action]

  const handler =
    action === 'signIn' ? onSignIn : action === 'none' ? undefined : onRetry

  return (
    <Alert
      tone={state === 'indeterminate' ? 'warning' : 'error'}
      title={title}
      actions={
        actionLabel && handler ? (
          <button type="button" className="mi-button mi-button--ghost mi-button--sm" onClick={handler}>
            {actionLabel}
          </button>
        ) : undefined
      }
    >
      {/* 상태 이름을 보조기술이 읽을 수 있게 남긴다. 색·아이콘만으로 구분하지 않는다. */}
      <span className="visually-hidden">{`화면 상태: ${state}`}</span>
    </Alert>
  )
}

const DEFAULT_MESSAGE: Record<
  ReturnType<typeof toAsyncState>['state'],
  string
> = {
  initial: '아직 요청하지 않았습니다.',
  loading: '불러오는 중입니다.',
  empty: '결과가 없습니다.',
  success: '완료했습니다.',
  validationError: '입력한 내용을 다시 확인해 주세요.',
  forbidden: '이 화면을 볼 권한이 없습니다.',
  conflict: '방금 상태가 바뀌었습니다. 최신 내용을 다시 확인해 주세요.',
  indeterminate: '결과를 확인하지 못했습니다. 처리 여부가 확정되지 않았습니다.',
  retryable: '일시적인 문제가 발생했습니다.',
  awaitingRecovery: '서비스를 일시적으로 이용할 수 없습니다.',
}
