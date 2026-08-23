import { useCallback, useEffect, useRef, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { Link, useParams } from 'react-router'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { Button } from '../../../../shared/ui/Button'
import { Alert, Loading } from '../../../../shared/ui/Feedback'
import { useIssueReservationCheckInQr } from '../api/queries'

const ROTATE_BEFORE_EXPIRY_MS = 5_000

/** raw QR credential을 화면 메모리에만 두고 만료 전에 회전한다. */
export function ReservationCheckInQrPage() {
  const { reservationId = '' } = useParams()
  const issue = useIssueReservationCheckInQr(reservationId)
  const [grant, setGrant] = useState<Awaited<ReturnType<typeof issue.mutateAsync>> | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const timer = useRef<number | null>(null)

  const refresh = useCallback(async () => {
    setMessage(null)
    try {
      const next = await issue.mutateAsync()
      setGrant(next)
      if (timer.current !== null) window.clearTimeout(timer.current)
      const delay = Math.max(1_000, new Date(next.expiresAt).getTime() - Date.now() - ROTATE_BEFORE_EXPIRY_MS)
      timer.current = window.setTimeout(() => void refresh(), delay)
    } catch {
      setGrant(null)
      setMessage('체크인 QR을 발급하지 못했습니다. 다시 시도해 주세요.')
    }
  }, [issue.mutateAsync])

  useEffect(() => {
    void refresh()
    return () => {
      if (timer.current !== null) window.clearTimeout(timer.current)
    }
  }, [refresh])

  return (
    <main className="mi-container mi-container--narrow reservation-check-in">
      <header className="mi-page-head">
        <p className="reservation-detail__breadcrumb">
          <Link to={CONSUMER_PATHS.reservationDetail.replace(':reservationId', reservationId)}>예약 상세로 돌아가기</Link>
        </p>
        <h1 className="mi-page-head__title">매장 체크인</h1>
        <p className="mi-page-head__lead">직원에게 QR을 보여 주세요.</p>
      </header>
      {issue.isPending && grant === null && <Loading label="체크인 QR을 발급하는 중입니다." />}
      {message !== null && (
        <Alert tone="error" title={message} actions={<Button variant="ghost" size="sm" onClick={() => void refresh()}>다시 시도</Button>} />
      )}
      {grant !== null && (
        <section className="mi-card reservation-check-in__card">
          <div className="mi-card__body mi-card__body--roomy">
            <div className="reservation-check-in__qr" aria-label="매장 체크인 QR">
              <QRCodeSVG value={grant.qrToken} size={240} level="M" />
            </div>
            <p>보안을 위해 QR은 자동으로 갱신됩니다.</p>
            <p className="reservation-check-in__expiry">
              현재 QR 만료: {new Date(grant.expiresAt).toLocaleTimeString('ko-KR')}
            </p>
            <Button variant="ghost" loading={issue.isPending} onClick={() => void refresh()}>
              지금 새로고침
            </Button>
          </div>
        </section>
      )}
    </main>
  )
}
