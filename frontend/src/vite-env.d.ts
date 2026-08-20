/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly MIRIYUM_PORTONE_STORE_ID?: string
  readonly MIRIYUM_PORTONE_CHANNEL_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
