import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

// backend는 CORS를 구성하지 않고 Refresh·CSRF 쿠키가 SameSite=Lax에 namespace별 Path로 한정된다.
// dev server가 /api를 프록시해 same-origin 조건을 만들어야 인증 흐름이 동작한다.
// 포트를 바꾸면 deploy/local/.env의 MIRIYUM_ALLOWED_ORIGIN도 함께 바꾼다.
const DEV_SERVER_PORT = 5173

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'MIRIYUM_')
  const backendOrigin = env.MIRIYUM_VITE_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [react()],
    server: {
      port: DEV_SERVER_PORT,
      // 포트가 이미 사용 중이면 조용히 다른 포트로 옮기지 않는다.
      // 오리진이 바뀌면 backend의 Origin 검증이 실패하므로 즉시 실패하는 편이 낫다.
      strictPort: true,
      proxy: {
        '/api': {
          target: backendOrigin,
          // upstream에는 대상 도메인 Host를 전달해 Nginx의 canonical-domain redirect를 피한다.
          // 브라우저가 보낸 Origin(http://localhost:5173)은 그대로 전달된다.
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyRes', (proxyResponse) => {
              const setCookie = proxyResponse.headers['set-cookie']
              if (setCookie === undefined) {
                return
              }

              // staging은 HTTPS라 state 쿠키가 Secure로 내려온다. 로컬 Vite는 HTTP이므로
              // 이 속성을 제거해야 브라우저가 콜백 세션 교환에 필요한 쿠키를 저장한다.
              const cookies = Array.isArray(setCookie) ? setCookie : [setCookie]
              proxyResponse.headers['set-cookie'] = cookies.map((cookie) =>
                cookie.replace(/;\s*Secure\b/gi, ''),
              )
            })
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
    },
  }
})
