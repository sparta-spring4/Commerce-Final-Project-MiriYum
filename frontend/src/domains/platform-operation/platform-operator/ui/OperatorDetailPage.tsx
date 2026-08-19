import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { isApiError } from '../../../../shared/api/apiError'
import { Badge } from '../../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  decideCapability,
  usePlatformOperatorAuth,
} from '../../../account/platform-operator/auth'
import {
  fetchOperatorAccount,
  operatorAccountQueryKeys,
  type OperatorAccountDetail,
} from '../api/operatorAccountApi'
import {
  OPERATOR_PERMISSION_LABEL,
  OPERATOR_ROLE_LABEL,
  OPERATOR_STATUS_LABEL,
  OPERATOR_STATUS_TONE,
  isDirectPermission,
} from '../model/operatorLabels'
import { OperatorAccessDenied } from './OperatorAccessDenied'
import { OperatorAuthorityForm } from './OperatorAuthorityForm'
import { OperatorSuspensionForm } from './OperatorSuspensionForm'
import './page.css'

/**
 * 운영자 계정 상세.
 *
 * 계약이 주는 것만 보여 준다. 이메일은 서버가 마스킹한 값이고, 원문·임시
 * 비밀번호·로그인 이력 목록은 계약에 없으므로 만들지 않는다. `lastLoginAt`은
 * 감사 원장의 마지막 성공 로그인 한 건이며 이력이 아니다.
 *
 * 유효 권한은 역할이 준 것과 직접 부여된 것을 구분해 표시한다. 합쳐 놓으면
 * 권한을 회수할 때 역할을 빼야 하는지 직접 권한을 빼야 하는지 알 수 없다.
 */
export function OperatorDetailPage() {
  const { apiClient, capabilities } = usePlatformOperatorAuth()
  const canSuspend =
    decideCapability(capabilities, 'OPERATOR_SUSPEND') === 'allowed'
  const params = useParams<{ operatorId: string }>()
  const operatorId = params.operatorId

  const accountQuery = useQuery({
    queryKey: operatorAccountQueryKeys.detail(operatorId ?? ''),
    queryFn: ({ signal }) =>
      fetchOperatorAccount(apiClient, operatorId!, signal),
    enabled:
      operatorId !== undefined &&
      decideCapability(capabilities, 'OPERATOR_AUTHORITY_MANAGE') === 'allowed',
  })

  if (operatorId === undefined) {
    return (
      <EmptyState
        title="잘못된 주소입니다."
        description="운영자 목록에서 다시 선택해 주세요."
      />
    )
  }

  const denied =
    accountQuery.isError && isApiError(accountQuery.error)
      ? accountQuery.error.status === 403
      : false

  return (
    <section aria-labelledby="operator-detail-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="operator-detail-heading">
            운영자 상세
          </h1>
          <p className="po-page__subtitle">
            <Link
              to={PLATFORM_OPERATOR_PATHS.operators}
              className="po-table__link"
            >
              운영자 목록으로
            </Link>
          </p>
        </div>
      </header>

      {accountQuery.isPending && (
        <Loading label="운영자 정보를 불러오는 중입니다." />
      )}

      {denied && <OperatorAccessDenied />}

      {accountQuery.isError && !denied && (
        <ErrorState
          error={accountQuery.error}
          onRetry={() => void accountQuery.refetch()}
        />
      )}

      {accountQuery.data !== undefined && (
        <>
          <OperatorSummary account={accountQuery.data} />
          <OperatorAuthorityView account={accountQuery.data} />
          <OperatorAuthorityForm
            key={`${accountQuery.data.operatorId}:${accountQuery.data.authorityVersion}`}
            account={accountQuery.data}
            onReplaced={() => void accountQuery.refetch()}
          />
          {canSuspend && (
            <OperatorSuspensionForm
              account={accountQuery.data}
              onSuspended={() => void accountQuery.refetch()}
            />
          )}
        </>
      )}
    </section>
  )
}

function OperatorSummary({ account }: { account: OperatorAccountDetail }) {
  return (
    <dl className="po-detail">
      <div className="po-detail__row">
        <dt>표시명</dt>
        <dd>{account.displayName}</dd>
      </div>
      <div className="po-detail__row">
        <dt>운영자 ID</dt>
        <dd>{account.operatorId}</dd>
      </div>
      <div className="po-detail__row">
        <dt>이메일</dt>
        {/* 서버가 마스킹한 값이다. 원문을 요청하거나 복원하지 않는다. */}
        <dd>{account.email}</dd>
      </div>
      <div className="po-detail__row">
        <dt>상태</dt>
        <dd>
          <Badge tone={OPERATOR_STATUS_TONE[account.status]}>
            {OPERATOR_STATUS_LABEL[account.status]}
          </Badge>
          {account.passwordChangeRequired && (
            <span className="po-table__note">최초 비밀번호 변경 전</span>
          )}
        </dd>
      </div>
      <div className="po-detail__row">
        <dt>권한 version</dt>
        <dd>{account.authorityVersion}</dd>
      </div>
      <div className="po-detail__row">
        <dt>최근 로그인</dt>
        <dd>
          {account.lastLoginAt == null
            ? '기록 없음'
            : formatTimestamp(account.lastLoginAt)}
        </dd>
      </div>
    </dl>
  )
}

/**
 * 역할·직접 권한·유효 권한을 나눠 보여 준다.
 *
 * 유효 권한 각 항목에 출처를 붙인다. "직접"이 아닌 것은 역할에서 온 것이므로,
 * 회수하려면 직접 권한이 아니라 역할을 조정해야 한다는 뜻이 된다.
 */
function OperatorAuthorityView({
  account,
}: {
  account: OperatorAccountDetail
}) {
  return (
    <section aria-labelledby="operator-authority-heading">
      <h2 className="po-section__title" id="operator-authority-heading">
        현재 권한
      </h2>

      <dl className="po-detail">
        <div className="po-detail__row">
          <dt>역할</dt>
          <dd>
            {account.roles.length === 0
              ? '없음'
              : account.roles
                  .map((role) => OPERATOR_ROLE_LABEL[role])
                  .join(', ')}
          </dd>
        </div>
        <div className="po-detail__row">
          <dt>직접 부여 권한</dt>
          <dd>
            {account.directPermissions.length === 0
              ? '없음'
              : account.directPermissions
                  .map((permission) => OPERATOR_PERMISSION_LABEL[permission])
                  .join(', ')}
          </dd>
        </div>
      </dl>

      <h3 className="po-subsection__title">유효 권한</h3>
      {account.effectivePermissions.length === 0 ? (
        <EmptyState title="유효 권한이 없습니다." />
      ) : (
        <div className="po-table-scroll">
          <table className="po-table">
            <caption className="po-table__caption">
              역할이 준 권한과 직접 부여된 권한을 합산한 결과입니다.
            </caption>
            <thead>
              <tr>
                <th scope="col">권한</th>
                <th scope="col">출처</th>
              </tr>
            </thead>
            <tbody>
              {account.effectivePermissions.map((permission) => (
                <tr key={permission}>
                  <th scope="row">
                    {OPERATOR_PERMISSION_LABEL[permission]}
                  </th>
                  <td>
                    {isDirectPermission(
                      permission,
                      account.directPermissions,
                    ) ? (
                      <Badge tone="attention">직접 부여</Badge>
                    ) : (
                      <Badge tone="neutral">역할</Badge>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
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
    timeStyle: 'short',
  })
}
