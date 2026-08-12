import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ROUTES, fillPath } from '../../../app/routes'
import { Badge } from '../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { useAdoptStoreFromRoute } from '../CurrentStoreProvider'
import { useOperatorCatalog } from '../api/queries'
import { useManagedMenus } from '../api/menuQueries'
import {
  MENU_FILTERS,
  MENU_FILTER_LABEL,
  matchesMenuFilter,
  primaryMenuVersion,
  type MenuFilter,
} from '../model/menuFilters'
import { storeErrorMessage } from '../model/storeErrors'
import {
  MENU_SELLING_STATUS_LABEL,
  MENU_VERSION_STATUS_LABEL,
  MENU_VISIBILITY_LABEL,
  type ManagedMenu,
} from '../model/types'
import { PageHeader } from './PageHeader'

/**
 * 메뉴 목록.
 *
 * 버전·노출·판매는 독립 축이므로 열을 나눠 보여 준다. 상태 변경 명령은 모두
 * 변경 사유를 요구하므로 목록에서 바로 실행하지 않고 상세 화면에서 다룬다.
 *
 * 제공 구간 수량(재고)은 다른 도메인이며 이 화면 범위 밖이다.
 */
export function MenuListPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const menus = useManagedMenus(storeId)
  const categories = useOperatorCatalog('menu-categories')
  const [filter, setFilter] = useState<MenuFilter>('ALL')

  const categoryName = (code: string) =>
    categories.data?.find((item) => item.code === code)?.displayName ?? code

  return (
    <>
      <PageHeader
        title="메뉴 관리"
        description="메뉴 내용과 게시 상태를 관리합니다."
        actions={
          <Link
            className="mi-button mi-button--primary"
            to={fillPath(ROUTES.storeOperatorMenuCreate, { storeId })}
          >
            새 메뉴 추가
          </Link>
        }
      />

      <section className="mi-card">
        <div className="mi-card__body">
          <fieldset>
            <legend className="op-day__group-title">상태 필터</legend>
            <div className="op-chip-set">
              {MENU_FILTERS.map((value) => (
                <button
                  type="button"
                  key={value}
                  className="mi-chip"
                  aria-pressed={filter === value}
                  onClick={() => setFilter(value)}
                >
                  {MENU_FILTER_LABEL[value]}
                </button>
              ))}
            </div>
          </fieldset>

          {menus.isPending && <Loading label="메뉴를 불러오는 중입니다." />}

          {menus.isError && (
            <ErrorState
              error={menus.error}
              message={storeErrorMessage(menus.error)}
              onRetry={() => void menus.refetch()}
            />
          )}

          {menus.isSuccess && (
            <MenuTable
              storeId={storeId}
              menus={menus.data.filter((menu) => matchesMenuFilter(menu, filter))}
              total={menus.data.length}
              categoryName={categoryName}
            />
          )}
        </div>
      </section>
    </>
  )
}

function MenuTable({
  storeId,
  menus,
  total,
  categoryName,
}: {
  storeId: string
  menus: readonly ManagedMenu[]
  total: number
  categoryName: (code: string) => string
}) {
  if (total === 0) {
    return (
      <EmptyState
        title="등록한 메뉴가 없습니다."
        description="새 메뉴를 추가하면 초안으로 저장되고, 게시 시점은 따로 정합니다."
      />
    )
  }
  if (menus.length === 0) {
    return (
      <EmptyState
        title="이 상태의 메뉴가 없습니다."
        description="다른 상태 필터를 선택해 보세요."
      />
    )
  }

  return (
    <div className="op-table-scroll">
      <table className="op-table">
        <caption className="visually-hidden">
          메뉴 목록. 버전 상태와 노출·판매 상태를 각각 표시합니다.
        </caption>
        <thead>
          <tr>
            <th scope="col">메뉴명</th>
            <th scope="col">주 카테고리</th>
            <th scope="col" className="op-table__numeric">
              가격
            </th>
            <th scope="col">버전</th>
            <th scope="col">노출</th>
            <th scope="col">판매</th>
            <th scope="col">관리</th>
          </tr>
        </thead>
        <tbody>
          {menus.map((menu) => {
            const version = primaryMenuVersion(menu)
            return (
              <tr key={menu.menuId}>
                <th scope="row">
                  {version?.name ?? `메뉴 #${menu.menuId}`}
                  {version?.representative === true && (
                    <>
                      {' '}
                      <Badge tone="attention">대표</Badge>
                    </>
                  )}
                </th>
                <td>
                  {version === null ? '—' : categoryName(version.primaryCategoryCode)}
                </td>
                <td className="op-table__numeric">
                  {version === null
                    ? '—'
                    : `${version.price.toLocaleString('ko-KR')}원`}
                </td>
                <td>
                  {menu.retired ? (
                    <Badge tone="neutral">운영 종료</Badge>
                  ) : (
                    <VersionBadges menu={menu} />
                  )}
                </td>
                <td>
                  <Badge
                    tone={menu.visibility === 'VISIBLE' ? 'positive' : 'neutral'}
                  >
                    {MENU_VISIBILITY_LABEL[menu.visibility]}
                  </Badge>
                </td>
                <td>
                  <Badge tone={sellingTone(menu.sellingStatus)}>
                    {MENU_SELLING_STATUS_LABEL[menu.sellingStatus]}
                  </Badge>
                </td>
                <td>
                  <Link
                    className="mi-button mi-button--ghost mi-button--sm"
                    to={fillPath(ROUTES.storeOperatorMenu, {
                      storeId,
                      menuId: menu.menuId,
                    })}
                  >
                    상세
                  </Link>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/** 한 메뉴가 여러 버전 슬롯을 동시에 가질 수 있으므로 모두 표시한다. */
function VersionBadges({ menu }: { menu: ManagedMenu }) {
  const slots = [menu.published, menu.scheduled, menu.draft].filter(
    (version): version is NonNullable<typeof version> => version != null,
  )

  if (slots.length === 0) {
    return <>—</>
  }

  // 뱃지를 나란히 두면 "게시됨 v3초안 v4"처럼 붙어 읽힌다. 간격을 명시한다.
  return (
    <span className="op-badge-row">
      {slots.map((version) => (
        <Badge
          key={`${version.status}-${version.versionNumber}`}
          tone={version.status === 'PUBLISHED' ? 'positive' : 'neutral'}
        >
          {`${MENU_VERSION_STATUS_LABEL[version.status]} v${version.versionNumber}`}
        </Badge>
      ))}
    </span>
  )
}

function sellingTone(
  status: ManagedMenu['sellingStatus'],
): 'positive' | 'negative' | 'neutral' {
  if (status === 'SELLING') {
    return 'positive'
  }
  return status === 'SOLD_OUT' ? 'negative' : 'neutral'
}
