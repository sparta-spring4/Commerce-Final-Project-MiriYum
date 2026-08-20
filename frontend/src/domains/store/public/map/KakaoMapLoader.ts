const KAKAO_MAP_SDK_URL = 'https://dapi.kakao.com/v2/maps/sdk.js'
const SCRIPT_ATTRIBUTE = 'data-miriyum-kakao-map'
const SDK_LOAD_TIMEOUT_MS = 10_000

export class KakaoMapLoader {
  private loadPromise: Promise<KakaoMapsNamespace> | null = null
  private activeAttempt: object | null = null

  load(appKey: string): Promise<KakaoMapsNamespace> {
    if (appKey.trim() === '') {
      return Promise.reject(
        new Error('Kakao 지도 JavaScript 키가 설정되지 않았습니다.'),
      )
    }

    if (this.loadPromise !== null) {
      return this.loadPromise
    }

    const attempt = {}
    this.activeAttempt = attempt
    this.loadPromise = new Promise<KakaoMapsNamespace>((resolve, reject) => {
      let script: HTMLScriptElement | null = null
      let timeoutId: ReturnType<typeof setTimeout> | null = null
      let settled = false

      const clearLoadTimeout = () => {
        if (timeoutId !== null) {
          clearTimeout(timeoutId)
        }
      }

      const removeScriptListeners = () => {
        script?.removeEventListener('load', resolveLoadedMaps)
        script?.removeEventListener('error', handleScriptError)
      }

      const rejectLoad = (error: Error) => {
        if (settled || this.activeAttempt !== attempt) {
          return
        }
        settled = true
        clearLoadTimeout()
        removeScriptListeners()
        script?.remove()
        this.activeAttempt = null
        this.loadPromise = null
        reject(error)
      }

      const resolveLoadedMaps = () => {
        const maps = window.kakao?.maps
        if (maps === undefined) {
          rejectLoad(new Error('Kakao 지도 SDK를 불러오지 못했습니다.'))
          return
        }

        maps.load(() => {
          if (settled || this.activeAttempt !== attempt) {
            return
          }
          settled = true
          clearLoadTimeout()
          removeScriptListeners()
          this.activeAttempt = null
          resolve(maps)
        })
      }

      const handleScriptError = () =>
        rejectLoad(new Error('Kakao 지도 SDK를 불러오지 못했습니다.'))

      timeoutId = setTimeout(
        () =>
          rejectLoad(
            new Error('Kakao 지도 SDK 로딩 시간이 초과되었습니다.'),
          ),
        SDK_LOAD_TIMEOUT_MS,
      )

      if (window.kakao?.maps !== undefined) {
        resolveLoadedMaps()
        return
      }

      script = document.createElement('script')
      script.setAttribute(SCRIPT_ATTRIBUTE, '')
      script.async = true
      script.src = `${KAKAO_MAP_SDK_URL}?appkey=${encodeURIComponent(appKey)}&autoload=false`
      script.addEventListener('load', resolveLoadedMaps, { once: true })
      script.addEventListener('error', handleScriptError, { once: true })
      document.head.append(script)
    })

    return this.loadPromise
  }
}

export const kakaoMapLoader = new KakaoMapLoader()
