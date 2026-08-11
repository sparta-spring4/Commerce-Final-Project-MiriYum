type MapFallbackProps = {
  reason: string
}

export function MapFallback({ reason }: MapFallbackProps) {
  return (
    <section aria-label="지도 안내">
      <p role="status">{reason}</p>
      <p>매장 목록에서 계속 확인할 수 있습니다.</p>
    </section>
  )
}
