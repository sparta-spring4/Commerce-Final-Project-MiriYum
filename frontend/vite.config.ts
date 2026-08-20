import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

// backend는 CORS를 구성하지 않고 Refresh·CSRF 쿠키가 SameSite=Lax에 namespace별 Path로 한정된다.
// dev server가 /api를 프록시해 same-origin 조건을 만들어야 인증 흐름이 동작한다.
// 포트를 바꾸면 deploy/local/.env의 MIRIYUM_ALLOWED_ORIGIN도 함께 바꾼다.
const DEV_SERVER_PORT = 5173
const LOCAL_ENV_DIR = resolve(process.cwd(), '../deploy/local')
const KAKAO_STATE_COOKIE = /^MIRIYUM_(?:CONSUMER|STORE_OPERATOR)_KAKAO_(?:LOGIN|LINK)_STATE=/

export function portOneBrowserDefines(env: Record<string, string>) {
  return {
    'import.meta.env.MIRIYUM_PORTONE_STORE_ID': JSON.stringify(
      env.MIRIYUM_PORTONE_STORE_ID ?? '',
    ),
    'import.meta.env.MIRIYUM_PORTONE_CHANNEL_KEY': JSON.stringify(
      env.MIRIYUM_PORTONE_CHANNEL_KEY ?? '',
    ),
  }
}

export function rewriteKakaoStateCookies(cookies: string[]): string[] {
  return cookies.map((cookie) => {
    if (!KAKAO_STATE_COOKIE.test(cookie)) {
      return cookie
    }
    return cookie.replace(/;\s*Secure\b/gi, '')
  })
}

export default defineConfig(({ mode }) => {
  const env = {
    ...loadEnv(mode, process.cwd(), 'MIRIYUM_'),
    ...loadEnv(mode, LOCAL_ENV_DIR, 'MIRIYUM_'),
  }
  const backendOrigin = env.MIRIYUM_VITE_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [react()],
    define: portOneBrowserDefines(env),
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

              // staging은 HTTPS라 Kakao state 쿠키가 Secure로 내려온다. 로컬 Vite는 HTTP이므로
              // state 쿠키에 한해서만 이 속성을 제거해야 콜백 세션 교환이 가능하다.
              const cookies = Array.isArray(setCookie) ? setCookie : [setCookie]
              proxyResponse.headers['set-cookie'] = rewriteKakaoStateCookies(cookies)
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
