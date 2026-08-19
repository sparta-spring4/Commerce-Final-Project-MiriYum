import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'

/**
 * 가로 스크롤 캐러셀.
 *
 * 시안의 좌우 원형 버튼을 실제로 동작하게 만든다. 버튼은 한 화면만큼 밀고
 * 양 끝에서는 비활성화한다. 장식용 버튼을 두지 않는다.
 */
/**
 * @param itemCount 트랙에 들어간 항목 수.
 *
 * 항목이 늘어도 트랙 자체의 박스 크기는 그대로다. 가로로 넘칠 뿐이라
 * ResizeObserver가 울리지 않는다. 실제로 카드 9장을 넣었는데 "다음" 버튼이
 * 계속 비활성인 버그가 났다. 항목 수를 의존성으로 받아 다시 측정한다.
 */
export function useCarousel(itemCount: number) {
  const trackRef = useRef<HTMLUListElement>(null)
  const [atStart, setAtStart] = useState(true)
  const [atEnd, setAtEnd] = useState(true)

  const sync = useCallback(() => {
    const track = trackRef.current
    if (track === null) {
      return
    }
    const max = track.scrollWidth - track.clientWidth
    setAtStart(track.scrollLeft <= 1)
    // 소수점 스크롤 위치 때문에 정확히 같아지지 않는다. 1px 여유를 둔다.
    setAtEnd(track.scrollLeft >= max - 1)
  }, [])

  useEffect(() => {
    const track = trackRef.current
    if (track === null) {
      return
    }
    sync()

    // 카드가 늦게 로드되면 스크롤 폭이 바뀐다. 크기 변화도 따라간다.
    if (typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(sync)
    observer.observe(track)
    return () => observer.disconnect()
  }, [sync, itemCount])

  const scrollByPage = useCallback((direction: 1 | -1) => {
    const track = trackRef.current
    if (track === null) {
      return
    }
    track.scrollBy({ left: direction * track.clientWidth, behavior: 'smooth' })
  }, [])

  return { trackRef, atStart, atEnd, sync, scrollByPage }
}

export function CarouselNav({
  label,
  atStart,
  atEnd,
  onScroll,
}: {
  label: string
  atStart: boolean
  atEnd: boolean
  onScroll: (direction: 1 | -1) => void
}) {
  return (
    <div className="home__carousel-nav">
      <button
        type="button"
        className="home__carousel-button"
        aria-label={`${label} 이전`}
        disabled={atStart}
        onClick={() => onScroll(-1)}
      >
        <span aria-hidden="true">‹</span>
      </button>
      <button
        type="button"
        className="home__carousel-button"
        aria-label={`${label} 다음`}
        disabled={atEnd}
        onClick={() => onScroll(1)}
      >
        <span aria-hidden="true">›</span>
      </button>
    </div>
  )
}

/**
 * 스크롤 트랙.
 *
 * 트랙 자체도 포커스를 받는다. 스크롤 영역을 키보드로 조작할 수 없으면
 * 마우스 없이 뒤쪽 카드에 닿지 못한다.
 */
export function CarouselTrack({
  label,
  trackRef,
  onScroll,
  children,
}: {
  label: string
  trackRef: RefObject<HTMLUListElement | null>
  onScroll: () => void
  children: ReactNode
}) {
  return (
    <ul
      className="home__store-track"
      ref={trackRef}
      onScroll={onScroll}
      tabIndex={0}
      aria-label={label}
    >
      {children}
    </ul>
  )
}
