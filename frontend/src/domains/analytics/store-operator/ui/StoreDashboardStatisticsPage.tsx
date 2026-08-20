import { useParams } from 'react-router'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard, SummaryList } from '../../../../app/shells/store-operator/OperatorPage'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useStoreDashboardStatistics } from '../api/queries'
import type { DashboardSnapshot, MetricCompleteness } from '../model/types'

const COMPLETE: MetricCompleteness = 'COMPLETE'

export function StoreDashboardStatisticsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)
  const query = useStoreDashboardStatistics(storeId)

  return <>
    <PageHeader title="운영 통계" description="한 기준 시각으로 생성된 오늘의 예약·웨이팅 운영 snapshot입니다." />
    {query.isPending && <Loading label="운영 통계를 불러오는 중입니다." />}
    {query.isError && <ErrorState error={query.error} message="운영 통계를 불러오지 못했습니다." onRetry={() => void query.refetch()} />}
    {query.isSuccess && <Statistics snapshot={query.data} />}
  </>
}

function Statistics({ snapshot }: { snapshot: DashboardSnapshot }) {
  const { metrics } = snapshot
  const incomplete = Object.values(metrics).some((metric) => metric.completeness !== COMPLETE)
  const waiting = metrics.waiting.value
  const noShow = metrics.noShow.value
  return <div className="op-stack">
    {incomplete && <Alert tone="warning" title="일부 지표의 집계가 완전하지 않습니다.">집계 불가 값을 0으로 표시하지 않습니다. 지표별 상태를 확인해 주세요.</Alert>}
    <SectionCard title={`${snapshot.businessDate} 기준`} hint={`기준 시각 ${formatDateTime(snapshot.asOf)} · 생성 ${formatDateTime(snapshot.generatedAt)}`}>
      <div className="op-shortcut-grid">
        <MetricCard text={`오늘 예약 ${count(metrics.todayReservationTeams.value)}팀`} completeness={metrics.todayReservationTeams.completeness} />
        <MetricCard text={`예약률 ${rate(metrics.reservationRate.value?.ratio)}`} completeness={metrics.reservationRate.completeness} />
        <MetricCard text={`팀 수용량 사용률 ${rate(metrics.teamCapacityUsageRate.value?.ratio)}`} completeness={metrics.teamCapacityUsageRate.completeness} />
        <MetricCard text={`취소율 ${rate(metrics.cancellationRate.value?.ratio)}`} completeness={metrics.cancellationRate.completeness} />
      </div>
    </SectionCard>
    <SectionCard title="현재 웨이팅">
      {waiting === null ? <p>웨이팅 집계 불가</p> : <SummaryList items={[
        { term: '팀', value: `대기 ${waiting.waitingTeams}팀 · 호출 ${waiting.calledTeams}팀` },
        { term: '인원', value: `대기 ${waiting.waitingPeople}명 · 호출 ${waiting.calledPeople}명` },
        { term: '최장 대기', value: waiting.longestWaitSeconds === null ? '활성 팀 없음' : `최장 대기 ${Math.floor(waiting.longestWaitSeconds / 60)}분` },
      ]} />}
    </SectionCard>
    <SectionCard title="노쇼">
      <p>{`예약 노쇼 확정 ${count(noShow.reservationConfirmed.value)}팀 · 웨이팅 노쇼 확정 ${count(noShow.waitingConfirmed.value)}팀`}</p>
      <p>{noShow.reservationCandidate.value === null ? '예약 노쇼 후보 집계 불가' : `예약 노쇼 후보 ${noShow.reservationCandidate.value}팀`}</p>
    </SectionCard>
  </div>
}

function MetricCard({ text, completeness }: { text: string; completeness: MetricCompleteness }) {
  return <article className="op-shortcut"><strong>{text}</strong><span>{completenessLabel(completeness)}</span></article>
}

function count(value: number | null): string { return value === null ? '집계 불가' : String(value) }
function rate(value: number | undefined): string { return value === undefined ? '집계 불가' : `${(value * 100).toFixed(1)}%` }
function completenessLabel(value: MetricCompleteness): string { return ({ COMPLETE: '집계 완료', DELAYED: '집계 지연', PARTIAL: '부분 집계', UNAVAILABLE: '집계 불가' } as const)[value] }
function formatDateTime(value: string): string { return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
