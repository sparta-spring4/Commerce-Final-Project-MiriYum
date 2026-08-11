type MapFallbackProps = {
  reason: string
  guidance?: string
  onRetry?: () => void
}

export function MapFallback({
  reason,
  guidance = '매장 목록에서 계속 확인할 수 있습니다.',
  onRetry,
}: MapFallbackProps) {
  return (
    <section aria-label="지도 안내">
      <p role="status">{reason}</p>
      <p>{guidance}</p>
      {onRetry !== undefined ? (
        <button type="button" onClick={onRetry}>
          지도 다시 시도
        </button>
      ) : null}
    </section>
  )
}
