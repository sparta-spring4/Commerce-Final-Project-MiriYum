import type { components } from '../../../../shared/api/generated/store-search'
import type { components as OnboardingComponents } from '../../../../shared/api/generated/store-onboarding'

/**
 * 매장 운영자 화면이 쓰는 계약 타입.
 *
 * 생성 타입에서만 가져오고 응답 필드를 손으로 다시 정의하지 않는다.
 * 화면 파일이 `components['schemas'][...]` 경로를 반복해서 쓰지 않게 여기 모은다.
 */

export type ManagedStore = components['schemas']['ManagedStore']
export type StoreOnboardingApplicationRequest =
  OnboardingComponents['schemas']['StoreOnboardingApplicationRequest']
export type StoreOnboardingApplication =
  OnboardingComponents['schemas']['StoreOnboardingApplicationData']
export type StoreOnboardingApplicationStatus =
  OnboardingComponents['schemas']['StoreOnboardingApplicationStatus']
export type StoreUpdateRequest = components['schemas']['StoreUpdateRequest']
export type StoreModes = components['schemas']['StoreModes']
export type Region = components['schemas']['Region']
export type OperationStatus = components['schemas']['OperationStatus']
export type EditableOperationStatus =
  components['schemas']['EditableOperationStatus']
export type CatalogItem = components['schemas']['CatalogItem']
export type CatalogCode = components['schemas']['CatalogCode']
export type PublicImage = components['schemas']['PublicImage']

export type DayOfWeek = components['schemas']['DailySchedule']['dayOfWeek']
export type TimeRange = components['schemas']['TimeRange']
export type DailySchedule = components['schemas']['DailySchedule']
export type DailyTimeSlots = components['schemas']['DailyTimeSlots']
export type OperatingHoursData = components['schemas']['OperatingHoursData']
export type ReservationTimeSlotsData =
  components['schemas']['ReservationTimeSlotsData']
export type ScheduleVersionStatus =
  components['schemas']['ScheduleVersionStatus']
export type SchedulePublicationRequest =
  components['schemas']['SchedulePublicationRequest']
export type PublicationMode = SchedulePublicationRequest['publicationMode']

export type RegularClosureData = components['schemas']['RegularClosureData']
export type TemporaryClosureData = components['schemas']['TemporaryClosureData']
export type TemporaryClosureReason =
  components['schemas']['TemporaryClosureCreateRequest']['reason']

export type ManagedMenu = components['schemas']['ManagedMenu']
export type MenuVersion = components['schemas']['MenuVersion']
export type MenuVersionStatus = MenuVersion['status']
export type MenuWriteRequest = components['schemas']['MenuWriteRequest']
export type MenuVisibility = ManagedMenu['visibility']
export type MenuSellingStatus = ManagedMenu['sellingStatus']
export type RepresentativeMenuSetting =
  components['schemas']['RepresentativeMenuSetting']
export type RepresentativeMenuReplaceRequest =
  components['schemas']['RepresentativeMenuReplaceRequest']
export type DisclosureRegistrationStatus =
  components['schemas']['DisclosureRegistrationStatus']
export type AllergenDisclosure = components['schemas']['AllergenDisclosure']
export type AllergenIngredientCode = AllergenDisclosure['ingredientCode']
export type AllergenContainmentStatus = AllergenDisclosure['status']
export type OriginDisclosure = components['schemas']['OriginDisclosure']

/** 공개 매장 상세. 게시된 영업시간을 참조 표시할 때만 읽는다. */
export type PublicStoreDetail = components['schemas']['StoreDetail']

export const DAY_OF_WEEK: readonly DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

export const DAY_LABEL: Record<DayOfWeek, string> = {
  MONDAY: '월요일',
  TUESDAY: '화요일',
  WEDNESDAY: '수요일',
  THURSDAY: '목요일',
  FRIDAY: '금요일',
  SATURDAY: '토요일',
  SUNDAY: '일요일',
}

export const REGION_LABEL: Record<Region, string> = {
  SEOUL: '서울',
  BUSAN: '부산',
  DAEGU: '대구',
  DAEJEON: '대전',
  GWANGJU: '광주',
}

export const OPERATION_STATUS_LABEL: Record<OperationStatus, string> = {
  OPEN: '영업 중',
  TEMPORARILY_CLOSED: '임시 휴업',
  CLOSED: '폐업',
}

/**
 * 버전 상태 표시명.
 *
 * `ACTIVATION_FAILED`는 예약 게시가 실패한 상태다. 성공으로 뭉개지 않는다.
 */
export const SCHEDULE_STATUS_LABEL: Record<ScheduleVersionStatus, string> = {
  DRAFT: '초안',
  SCHEDULED: '게시 예약',
  ACTIVE: '게시됨',
  RETIRED: '종료',
  ACTIVATION_FAILED: '게시 실패',
}

export const MENU_VERSION_STATUS_LABEL: Record<MenuVersionStatus, string> = {
  DRAFT: '초안',
  SCHEDULED: '게시 예약',
  PUBLISHED: '게시됨',
  RETIRED: '운영 종료',
}

export const MENU_VISIBILITY_LABEL: Record<MenuVisibility, string> = {
  VISIBLE: '공개',
  HIDDEN: '비공개',
}

export const MENU_SELLING_STATUS_LABEL: Record<MenuSellingStatus, string> = {
  SELLING: '판매 중',
  SOLD_OUT: '품절',
  PAUSED: '판매 중지',
}

export const TEMPORARY_CLOSURE_REASON_LABEL: Record<
  TemporaryClosureReason,
  string
> = {
  MAINTENANCE: '시설 정비',
  STAFFING: '인력 사정',
  PRIVATE_EVENT: '단체·행사 대절',
  OTHER: '기타',
}

/** 식약처 표시 대상 알레르기 유발 원재료. 계약 enum과 순서까지 맞춘다. */
export const ALLERGEN_LABEL: Record<AllergenIngredientCode, string> = {
  EGG: '난류',
  MILK: '우유',
  BUCKWHEAT: '메밀',
  PEANUT: '땅콩',
  SOYBEAN: '대두',
  WHEAT: '밀',
  MACKEREL: '고등어',
  CRAB: '게',
  SHRIMP: '새우',
  PORK: '돼지고기',
  PEACH: '복숭아',
  TOMATO: '토마토',
  SULFITES: '아황산류',
  WALNUT: '호두',
  CHICKEN: '닭고기',
  BEEF: '쇠고기',
  SQUID: '오징어',
  SHELLFISH: '조개류',
  PINE_NUT: '잣',
}

export const ALLERGEN_CONTAINMENT_LABEL: Record<
  AllergenContainmentStatus,
  string
> = {
  CONTAINS: '함유',
  MAY_CONTAIN: '혼입 가능',
}
