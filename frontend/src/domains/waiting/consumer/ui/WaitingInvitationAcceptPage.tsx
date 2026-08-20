import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  WAITING_JOIN_GUIDANCE,
  validateInvitationCode,
  type WaitingJoinErrorView,
} from '../model/partyViewState'
import './waitingParty.css'

/** 합류 진행 단계. */
export type WaitingJoinProgress = 'idle' | 'submitting' | 'succeeded'

interface Props {
  progress: WaitingJoinProgress
  error?: WaitingJoinErrorView | null
  /** 사용자가 입력한 코드를 그대로 넘긴다. 컨테이너가 요청을 소유한다. */
  onAcceptInvitation: (invitationCode: string) => void
  onRetry: () => void
}

/**
 * 초대 코드를 직접 입력해 기존 웨이팅 일행에 합류하는 화면.
 *
 * 로그인한 사용자만 쓰는 화면이지만, 보호 route 등록과 로그인 복귀는 라우팅
 * 연결 작업이 소유한다. 이 컴포넌트는 입력과 상태 표현만 갖는다.
 *
 * 초대 코드는 URL·query·storage로 받지 않는다. 링크에 담기면 브라우저 이력과
 * referer에 남고, 저장하면 만료된 코드로 다시 시도하게 된다. 코드는 사용자가
 * 전달받아 직접 입력하는 값으로만 다룬다.
 */
export function WaitingInvitationAcceptPage({
  progress,
  error = null,
  onAcceptInvitation,
  onRetry,
}: Props) {
  const [code, setCode] = useState('')
  const [attempted, setAttempted] = useState(false)

  const submitting = progress === 'submitting'
  const succeeded = progress === 'succeeded'

  /* 들어오자마자 오류 문구를 띄우지 않는다. 한 번 제출해 본 뒤에만 보여 준다. */
  const validationError = attempted ? validateInvitationCode(code) : null
  const guidance = error === null ? null : WAITING_JOIN_GUIDANCE[error.code]

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setAttempted(true)

    /*
     * 진행 중이거나 이미 합류한 요청은 다시 보내지 않는다. 버튼도 비활성화하지만
     * Enter 연타가 비활성화 사이를 빠져나갈 수 있어 핸들러에서 한 번 더 막는다.
     */
    if (submitting || succeeded) {
      return
    }
    if (validateInvitationCode(code) !== null) {
      return
    }
    onAcceptInvitation(code.trim())
  }

  return (
    <div className="mi-container mi-container--narrow waiting-join">
      <header className="mi-page-head">
        <h1 className="mi-page-head__title">웨이팅 일행 합류</h1>
        <p className="mi-page-head__lead">
          대표자에게 받은 초대 코드를 입력하면 기존 웨이팅에 함께 참여합니다. 새
          웨이팅이 만들어지지 않고 순번도 그대로 유지됩니다.
        </p>
      </header>

      {succeeded ? (
        <Alert tone="info" title="일행으로 합류했습니다.">
          <p>
            현재 웨이팅 화면에서 순번과 일행 목록을 확인할 수 있습니다.
          </p>
        </Alert>
      ) : (
        <section className="mi-card" aria-label="초대 코드 입력">
          <div className="mi-card__body">
            <form className="waiting-join__form" onSubmit={handleSubmit} noValidate>
              <TextField
                label="초대 코드"
                name="invitationCode"
                value={code}
                required
                /*
                 * 코드를 브라우저가 기억하지 않게 한다. 한 번만 쓰는 값이라
                 * 다음 사용자에게 제안되면 만료된 코드로 시도하게 된다.
                 */
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                disabled={submitting}
                help="전달받은 코드를 그대로 입력해 주세요. 발급 후 15분 동안만 쓸 수 있습니다."
                error={validationError}
                onChange={(event) => setCode(event.target.value)}
              />

              {guidance !== null && error !== null && (
                <Alert
                  tone="error"
                  title={error.message ?? guidance.title}
                  actions={
                    guidance.retryable ? (
                      <Button variant="ghost" size="sm" onClick={onRetry}>
                        다시 시도
                      </Button>
                    ) : undefined
                  }
                >
                  <p>{guidance.description}</p>
                </Alert>
              )}

              <Button
                type="submit"
                variant="primary"
                size="lg"
                block
                loading={submitting}
                disabled={submitting}
              >
                웨이팅 일행으로 합류
              </Button>
            </form>
          </div>
        </section>
      )}
    </div>
  )
}
