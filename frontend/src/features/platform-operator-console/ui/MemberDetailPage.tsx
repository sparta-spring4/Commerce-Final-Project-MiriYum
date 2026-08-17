import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ROUTES } from '../../../app/routes'
import { Badge, type BadgeTone } from '../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from '../../platform-operator-auth'
import {
  fetchMember,
  memberQueryKeys,
  type AccountType,
  type MemberStatus,
  type SanctionLevel,
} from '../api/memberSupportApi'
import './page.css'

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  CONSUMER: '일반 사용자',
  STORE_OPERATOR: '매장 운영자',
}

const STATUS_LABEL: Record<MemberStatus, string> = {
  ACTIVE: '활성',
  PASSWORD_RESET_REQUIRED: '비밀번호 재설정 필요',
  FEATURE_RESTRICTED: '기능 제한',
  TEMPORARILY_SUSPENDED: '기간 정지',
  PERMANENTLY_SUSPENDED: '영구 정지',
}

const STATUS_TONE: Record<MemberStatus, BadgeTone> = {
  ACTIVE: 'positive',
  PASSWORD_RESET_REQUIRED: 'attention',
  FEATURE_RESTRICTED: 'attention',
  TEMPORARILY_SUSPENDED: 'negative',
  PERMANENTLY_SUSPENDED: 'negative',
}

const SANCTION_LEVEL_LABEL: Record<SanctionLevel, string> = {
  WARNING: '경고',
  FEATURE_RESTRICTION: '기능 제한',
  TEMPORARY_SUSPENSION: '기간 정지',
  PERMANENT_SUSPENSION: '영구 정지',
}

const RESTRICTED_FEATURE_LABEL: Record<string, string> = {
  RESERVATION: '예약',
  WAITING: '웨이팅',
  PICKUP: '픽업',
  STORE_OPERATION: '매장 운영',
  MENU_OPERATION: '메뉴 운영',
}

/**
 * 회원 상세.
 *
 * 계약이 주는 것은 계정 유형·식별자·상태·가입일·support version·활성 제재뿐이다.
 * 이름·이메일·전화번호는 응답에 없다. 최소 식별정보만 다루는 조회라서 그렇다.
 *
 * `supportVersion`을 화면에 보여 준다. 제재·복구 명령이 이 값을 `If-Match`로
 * 보내야 하고, 값이 낡으면 서버가 409로 거절한다. 운영자가 다른 탭에서 상태가
 * 바뀐 것을 알아차릴 수 있어야 한다.
 */
export function MemberDetailPage() {
  const { apiClient } = usePlatformOperatorAuth()
  const params = useParams<{ accountType: string; accountId: string }>()

  const accountType = params.accountType
  const accountId = params.accountId

  const isKnownAccountType =
    accountType === 'CONSUMER' || accountType === 'STORE_OPERATOR'

  const memberQuery = useQuery({
    queryKey: memberQueryKeys.detail(
      accountType as AccountType,
      accountId ?? '',
    ),
    queryFn: ({ signal }) =>
      fetchMember(apiClient, accountType as AccountType, accountId!, signal),
    enabled: isKnownAccountType && accountId !== undefined,
  })

  if (!isKnownAccountType || accountId === undefined) {
    return (
      <EmptyState
        title="잘못된 주소입니다."
        description="회원 목록에서 다시 선택해 주세요."
      />
    )
  }

  return (
    <section aria-labelledby="member-detail-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="member-detail-heading">
            회원 상세
          </h1>
          <p className="po-page__subtitle">
            <Link
              to={ROUTES.platformOperatorMembers}
              className="po-table__link"
            >
              회원 목록으로
            </Link>
          </p>
        </div>
      </header>

      {memberQuery.isPending && (
        <Loading label="회원 정보를 불러오는 중입니다." />
      )}

      {/*
        404를 "다른 유형에는 있을 수 있다"는 안내로 바꾸지 않는다. 서버가 권한
        범위 밖의 대상도 404로 감추므로, 화면이 없음과 볼 수 없음을 갈라 말하면
        존재 여부가 새어 나간다.
      */}
      {memberQuery.isError && (
        <ErrorState
          error={memberQuery.error}
          onRetry={() => void memberQuery.refetch()}
        />
      )}

      {memberQuery.data !== undefined && (
        <>
          <dl className="po-detail">
            <div className="po-detail__row">
              <dt>식별자</dt>
              <dd>{memberQuery.data.accountId}</dd>
            </div>
            <div className="po-detail__row">
              <dt>계정 유형</dt>
              <dd>{ACCOUNT_TYPE_LABEL[memberQuery.data.accountType]}</dd>
            </div>
            <div className="po-detail__row">
              <dt>상태</dt>
              <dd>
                <Badge tone={STATUS_TONE[memberQuery.data.status]}>
                  {STATUS_LABEL[memberQuery.data.status]}
                </Badge>
              </dd>
            </div>
            <div className="po-detail__row">
              <dt>가입일</dt>
              <dd>{formatDate(memberQuery.data.joinedAt)}</dd>
            </div>
            <div className="po-detail__row">
              <dt>support version</dt>
              <dd>{memberQuery.data.supportVersion}</dd>
            </div>
          </dl>

          <section aria-labelledby="member-sanctions-heading">
            <h2 className="po-section__title" id="member-sanctions-heading">
              활성 제재
            </h2>
            {memberQuery.data.activeSanctions.length === 0 ? (
              <EmptyState title="활성 제재가 없습니다." />
            ) : (
              <div className="po-table-scroll">
                <table className="po-table">
                  <thead>
                    <tr>
                      <th scope="col">수준</th>
                      <th scope="col">제한 기능</th>
                      <th scope="col">종료</th>
                    </tr>
                  </thead>
                  <tbody>
                    {memberQuery.data.activeSanctions.map((sanction, index) => (
                      <tr key={`${sanction.level}:${index}`}>
                        <th scope="row">
                          {SANCTION_LEVEL_LABEL[sanction.level]}
                        </th>
                        <td>
                          {sanction.restrictedFeatures.length === 0
                            ? '해당 없음'
                            : sanction.restrictedFeatures
                                .map(
                                  (feature) =>
                                    RESTRICTED_FEATURE_LABEL[feature] ?? feature,
                                )
                                .join(', ')}
                        </td>
                        <td>
                          {sanction.endsAt == null
                            ? '기한 없음'
                            : formatDate(sanction.endsAt)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </section>
  )
}

function formatDate(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleDateString('ko-KR', { dateStyle: 'medium' })
}
