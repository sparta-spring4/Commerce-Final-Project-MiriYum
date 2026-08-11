import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

/**
 * 렌더링 예외를 잡아 빈 화면으로 끝나지 않게 한다.
 *
 * 오류 원문은 화면에 표시하지 않는다. 내부 구조나 다른 사용자의 상태가 새어 나갈 수 있다.
 * 토큰·비밀·개인정보를 클라이언트 로그에 남기지 않으므로 error 객체도 기록하지 않는다.
 */
export class AppErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(_error: Error, _info: ErrorInfo): void {
    // 의도적으로 비워 둔다. 관측 도구 연결은 승인된 단계에서 추가한다.
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <main>
          <h1>화면을 표시하지 못했습니다</h1>
          <p>잠시 후 다시 시도해 주세요.</p>
          <button type="button" onClick={() => this.setState({ hasError: false })}>
            다시 시도
          </button>
        </main>
      )
    }
    return this.props.children
  }
}
