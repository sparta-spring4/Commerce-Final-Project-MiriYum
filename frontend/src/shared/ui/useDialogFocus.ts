import { useEffect, useRef } from 'react'

/** Tab 순환에 포함되는 요소. `disabled`와 `tabindex="-1"`은 제외한다. */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

/**
 * 모달 다이얼로그의 포커스 관리.
 *
 * 세 가지를 한다.
 *
 * 1. 열릴 때 다이얼로그 안 첫 요소로 포커스를 옮긴다. 그러지 않으면 화면
 *    낭독기가 다이얼로그가 열린 사실을 알리지 못하고, 키보드 사용자는 Tab을
 *    여러 번 눌러야 도달한다.
 * 2. Tab을 다이얼로그 안에서 순환시킨다. 트랩이 없으면 뒤에 있는 폼으로 이동해
 *    모달 밖 값을 바꿀 수 있다.
 * 3. 닫힐 때 열기 전 요소로 포커스를 되돌린다. 되돌리지 않으면 포커스가 문서
 *    맨 앞으로 튀어 맥락을 잃는다.
 *
 * ESC 처리는 호출자가 넘긴다. 처리 중에는 닫으면 안 되는 경우가 있어서
 * 이 hook이 정책을 정하지 않는다.
 */
export function useDialogFocus<T extends HTMLElement>({
  onEscape,
  escapeEnabled,
}: {
  onEscape: () => void
  /** 처리 중에는 false를 넘겨 ESC로 닫히지 않게 한다. */
  escapeEnabled: boolean
}) {
  const containerRef = useRef<T | null>(null)
  const restoreFocusRef = useRef<Element | null>(null)

  // 열기 직전의 포커스를 기억하고, 닫힐 때 되돌린다.
  useEffect(() => {
    restoreFocusRef.current = document.activeElement
    const container = containerRef.current
    if (container !== null) {
      const focusable = container.querySelectorAll<HTMLElement>(
        FOCUSABLE_SELECTOR,
      )
      // 첫 입력이 있으면 그쪽, 없으면 컨테이너 자체로 보낸다.
      if (focusable.length > 0) {
        focusable[0].focus()
      } else {
        container.focus()
      }
    }

    return () => {
      const restore = restoreFocusRef.current
      if (restore instanceof HTMLElement && restore.isConnected) {
        restore.focus()
      }
    }
  }, [])

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        if (escapeEnabled) {
          event.preventDefault()
          onEscape()
        }
        return
      }
      if (event.key !== 'Tab') {
        return
      }
      const container = containerRef.current
      if (container === null) {
        return
      }
      /*
       * 가시성을 offsetParent로 걸러내지 않는다. 그 값은 레이아웃에 의존해서
       * 계산 결과가 없는 환경에서는 전부 null이 되고, 그러면 목록이 비어
       * 트랩이 조용히 꺼진다. 선택자가 이미 disabled와 tabindex="-1"을
       * 제외하므로 숨긴 컨트롤은 애초에 여기 들어오지 않는다.
       */
      const focusable = Array.from(
        container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      )
      if (focusable.length === 0) {
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement

      // 경계에서만 개입한다. 중간에서는 브라우저 기본 이동을 그대로 둔다.
      if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
        return
      }
      if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onEscape, escapeEnabled])

  return containerRef
}
