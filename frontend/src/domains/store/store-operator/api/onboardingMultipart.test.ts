import { describe, expect, it } from 'vitest'
import type { StoreOnboardingApplicationRequest } from '../model/types'
import { buildStoreOnboardingMultipart } from './queries'

const application: StoreOnboardingApplicationRequest = {
  name: '카페 에비뉴',
  description: '',
  region: 'SEOUL',
  address: '서울 강남구 테헤란로 152',
  timeZoneId: 'Asia/Seoul',
  storeCategoryCode: 'CAFE_BAKERY',
  tagCodes: [],
  modes: {
    reservationEnabled: true,
    menuHoldEnabled: false,
    pickupEnabled: false,
  },
  businessRegistrationNumber: '1234567890',
  legalBusinessName: '미리윰 주식회사',
  representativeName: '김대표',
  openingDate: '2026-08-21',
  primaryBusinessCategory: '음식점업',
  primaryBusinessItem: '카페',
  applicantSelfAttested: true,
  requiredTermsAgreed: true,
}

describe('입점 신청 multipart 조립', () => {
  it('계약 JSON과 원본 사업자등록증을 각각 한 part로 보존한다', async () => {
    const evidence = new File(['certificate'], 'business-registration.png', {
      type: 'image/png',
    })
    const multipart = buildStoreOnboardingMultipart(application, evidence)
    const applicationPart = multipart.get('application') as File

    expect(applicationPart.type).toBe('application/json')
    expect(JSON.parse(await readFile(applicationPart))).toEqual(application)
    expect(multipart.get('businessRegistrationEvidence')).toBe(evidence)
  })
})

function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error)
    reader.onload = () => resolve(String(reader.result))
    reader.readAsText(file)
  })
}
