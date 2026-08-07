import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// backend는 CORS를 구성하지 않고 Refresh·CSRF 쿠키가 SameSite=Lax에 namespace별 Path로 한정된다.
// dev server가 /api를 프록시해 same-origin 조건을 만들어야 인증 흐름이 동작한다.
// 포트를 바꾸면 deploy/local/.env의 MIRIYUM_ALLOWED_ORIGIN도 함께 바꾼다.
const DEV_SERVER_PORT = 5173
const BACKEND_ORIGIN = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: DEV_SERVER_PORT,
    // 포트가 이미 사용 중이면 조용히 다른 포트로 옮기지 않는다.
    // 오리진이 바뀌면 backend의 Origin 검증이 실패하므로 즉시 실패하는 편이 낫다.
    strictPort: true,
    proxy: {
      '/api': {
        target: BACKEND_ORIGIN,
        // Origin 헤더를 dev server 오리진 그대로 전달해 backend가 허용 오리진으로 인식하게 한다.
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
  },
})
