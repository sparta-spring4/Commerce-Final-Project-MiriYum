import { Button } from './Button'

interface Props {
  /** 0부터 시작하는 현재 페이지 번호. 공통 PageMetadata와 같은 기준이다. */
  number: number
  totalPages: number
  totalElements: number
  hasNext: boolean
  /**
   * 전체 개수의 단위 명사. 목록이 세는 대상이 화면마다 다르다.
   * 기본값은 매장 검색에서 쓰는 "곳"이다.
   */
  unitLabel?: string
  onChange: (nextPage: number) => void
}

/**
 * 목록 페이지 이동.
 *
 * 서버 계약이 0 기반 page와 hasNext를 함께 주므로 마지막 페이지 판정을
 * totalPages 계산으로 추측하지 않고 hasNext를 그대로 쓴다.
 */
export function Pagination({
  number,
  totalPages,
  totalElements,
  hasNext,
  unitLabel = '곳',
  onChange,
}: Props) {
  if (totalElements === 0) {
    return null
  }

  return (
    <nav className="mi-pagination" aria-label="페이지 이동">
      <Button
        variant="ghost"
        size="sm"
        disabled={number <= 0}
        onClick={() => onChange(number - 1)}
      >
        이전
      </Button>
      <p className="mi-pagination__status" aria-live="polite">
        {`${totalPages === 0 ? 1 : number + 1} / ${totalPages === 0 ? 1 : totalPages} 페이지 · 전체 ${totalElements}${unitLabel}`}
      </p>
      <Button
        variant="ghost"
        size="sm"
        disabled={!hasNext}
        onClick={() => onChange(number + 1)}
      >
        다음
      </Button>
    </nav>
  )
}
