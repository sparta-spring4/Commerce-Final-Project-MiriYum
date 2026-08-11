const KAKAO_MAP_SDK_URL = 'https://dapi.kakao.com/v2/maps/sdk.js'
const SCRIPT_ATTRIBUTE = 'data-miriyum-kakao-map'

export class KakaoMapLoader {
  private loadPromise: Promise<KakaoMapsNamespace> | null = null

  load(appKey: string): Promise<KakaoMapsNamespace> {
    if (appKey.trim() === '') {
      return Promise.reject(
        new Error('Kakao 지도 JavaScript 키가 설정되지 않았습니다.'),
      )
    }

    if (this.loadPromise !== null) {
      return this.loadPromise
    }

    this.loadPromise = new Promise<KakaoMapsNamespace>((resolve, reject) => {
      const resolveLoadedMaps = () => {
        const maps = window.kakao?.maps
        if (maps === undefined) {
          reject(new Error('Kakao 지도 SDK를 불러오지 못했습니다.'))
          return
        }

        maps.load(() => resolve(maps))
      }

      if (window.kakao?.maps !== undefined) {
        resolveLoadedMaps()
        return
      }

      const script = document.createElement('script')
      script.setAttribute(SCRIPT_ATTRIBUTE, '')
      script.async = true
      script.src = `${KAKAO_MAP_SDK_URL}?appkey=${encodeURIComponent(appKey)}&autoload=false`
      script.addEventListener('load', resolveLoadedMaps, { once: true })
      script.addEventListener(
        'error',
        () => reject(new Error('Kakao 지도 SDK를 불러오지 못했습니다.')),
        { once: true },
      )
      document.head.append(script)
    })

    return this.loadPromise
  }
}

export const kakaoMapLoader = new KakaoMapLoader()
