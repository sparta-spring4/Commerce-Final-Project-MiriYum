import { useNavigate, useParams } from 'react-router'
import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { useCurrentStore } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import { Button } from '../../../../shared/ui/Button'
import { Alert, Loading } from '../../../../shared/ui/Feedback'
import { useStoreOnboardingApplication } from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import type { StoreOnboardingApplicationStatus } from '../model/types'

const STATUS_LABEL: Record<StoreOnboardingApplicationStatus, string> = {
  RECEIVED: '신청 접수',
  EVIDENCE_PENDING: '등록증 저장 중',
  AUTO_CHECKING: '자동 확인 중',
  REVIEW_READY: '심사 준비 완료',
  UNDER_REVIEW: '심사 중',
  CHANGES_REQUESTED: '보완 요청',
  AUTO_APPROVED: '자동 승인',
  APPROVED: '승인',
  REJECTED: '반려',
}

export function StoreOnboardingStatusPage() {
  const { applicationId = '' } = useParams()
  const application = useStoreOnboardingApplication(applicationId)
  const { selectStore } = useCurrentStore()
  const navigate = useNavigate()

  function enterStore(storeId: string) {
    selectStore(storeId)
    void navigate(fillPath(STORE_OPERATOR_PATHS.store, { storeId }))
  }

  return (
    <>
      <PageHeader
        title="입점 신청 상태"
        description="등록증 자동 확인과 심사 진행 상태를 여기서 확인할 수 있습니다."
      />
      {application.isPending ? (
        <Loading label="입점 신청 상태를 불러오는 중입니다." />
      ) : application.isError ? (
        <Alert
          tone="error"
          title={storeErrorMessage(application.error)}
          actions={
            <Button variant="ghost" onClick={() => void application.refetch()}>
              다시 시도
            </Button>
          }
        />
      ) : (
        <SectionCard title="현재 진행 상태" icon="check">
          <div className="op-stack">
            <p>
              <strong>{STATUS_LABEL[application.data.status]}</strong>
            </p>
            <p>{application.data.nextAction}</p>
            <p className="op-section__hint">
              신청 번호 {application.data.applicationId} · 버전{' '}
              {application.data.applicationVersion}
            </p>
            {application.data.storeId !== null && (
              <div className="op-actions">
                <Button
                  variant="primary"
                  onClick={() => enterStore(application.data.storeId as string)}
                >
                  매장 관리로 이동
                </Button>
              </div>
            )}
          </div>
        </SectionCard>
      )}
    </>
  )
}
