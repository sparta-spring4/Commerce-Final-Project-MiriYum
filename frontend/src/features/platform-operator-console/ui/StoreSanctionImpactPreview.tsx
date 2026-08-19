import { Alert } from '../../../shared/ui/Feedback'
import type { StoreSanctionImpactPreview as ImpactPreview } from '../api/storeAdminApi'
import './page.css'

/**
 * 거래 영향 미리보기 결과.
 *
 * **이 숫자는 예상치이며 미리보기 생성이 취소·환불을 실행하지 않는다.**
 * 화면이 그 구분을 흐리면 운영자가 이미 처리된 줄 알고 다음 단계를 건너뛴다.
 *
 * `digest`와 두 version은 제재 실행 때 그대로 되돌려 보낸다. 그 사이 상태가
 * 바뀌면 서버가 불일치로 거절하므로 만료 시각을 함께 보여 준다.
 */
export function StoreSanctionImpactPreviewView({
  preview,
  expired,
}: {
  preview: ImpactPreview
  expired: boolean
}) {
  return (
    <section aria-labelledby="impact-preview-heading">
      <h3 className="po-subsection__title" id="impact-preview-heading">
        예상 거래 영향
      </h3>

      <Alert tone="info" title="아직 아무것도 취소되지 않았습니다.">
        아래는 제재를 적용했을 때 영향을 받을 건수의 예상치입니다. 미리보기를
        만드는 것만으로는 예약 취소나 환불이 실행되지 않습니다.
      </Alert>

      {expired && (
        <Alert tone="warning" title="미리보기가 만료됐습니다.">
          만료된 미리보기로는 제재를 실행할 수 없습니다. 다시 계산해 주세요.
        </Alert>
      )}

      <div className="po-table-scroll">
        <table className="po-table">
          <caption className="po-table__caption">
            미리보기 {preview.previewId} · 만료 {formatTimestamp(preview.expiresAt)}
          </caption>
          <thead>
            <tr>
              <th scope="col">항목</th>
              <th scope="col">건수</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th scope="row">확정 예약</th>
              <td>{preview.confirmedReservationCount}</td>
            </tr>
            <tr>
              <th scope="row">활성 웨이팅 팀</th>
              <td>{preview.activeWaitingTeamCount}</td>
            </tr>
            <tr>
              <th scope="row">확정 픽업</th>
              <td>{preview.confirmedPickupCount}</td>
            </tr>
            <tr>
              <th scope="row">미종결 결제</th>
              <td>{preview.unsettledPaymentCount}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <dl className="po-detail">
        <div className="po-detail__row">
          <dt>case version</dt>
          <dd>{preview.caseVersion}</dd>
        </div>
        <div className="po-detail__row">
          <dt>enforcement version</dt>
          <dd>{preview.storeEnforcementVersion}</dd>
        </div>
      </dl>
    </section>
  )
}

function formatTimestamp(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleString('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'medium',
  })
}
