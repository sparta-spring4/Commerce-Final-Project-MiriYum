import { useNavigate, useParams } from 'react-router'
import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { useCurrentStore } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import { Button } from '../../../../shared/ui/Button'
import { Alert, Loading } from '../../../../shared/ui/Feedback'
import { useStoreOnboardingApplication } from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import type {
  StoreOnboardingApplicationStatus,
  StoreOnboardingNextAction,
} from '../model/types'

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

const NEXT_ACTION_LABEL: Record<StoreOnboardingNextAction, string> = {
  WAIT: '자동 확인 또는 심사 진행을 기다려 주세요.',
  UPLOAD_EVIDENCE: '사업자등록증을 제출해 주세요.',
  SUBMIT_CHANGES: '요청된 보완 사항을 확인하고 수정본을 제출해 주세요.',
  COMPLETE: '입점 승인이 완료되었습니다. 매장 관리를 시작할 수 있습니다.',
  NONE: '이 신청은 더 진행할 수 없습니다. 필요한 경우 고객센터에 문의해 주세요.',
  MANUAL_OPERATIONS_REVIEW:
    '자동 확인이 완료되지 않아 운영자 심사를 기다리고 있습니다.',
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
            <p>{NEXT_ACTION_LABEL[application.data.nextAction]}</p>
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
