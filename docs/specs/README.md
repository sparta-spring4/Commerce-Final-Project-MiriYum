# 기능 명세

기능 명세는 기능의 durable한 사용자 행위, 계약과 인수 조건의 단일 원본이다. 새 명세는 [템플릿](_template/spec.md)을 복사해 기능 단위 디렉터리에 작성한다.

## 소유 경계

- 기능 명세는 관련 정책 ID, 사용자 관점의 기능 동작, 권한, 정상·오류 흐름, API·데이터 계약, migration·호환성 요구와 인수 조건을 소유한다.
- 구현을 위한 일회성 범위와 책임자는 GitHub Issue가 소유한다.
- lifecycle 상태는 단일 Project `Status` field가 소유한다.
- 명령, 결과와 검증 증빙은 Pull Request와 CI가 소유한다.

지속적인 아키텍처 결정은 [ADR](../adr/)에 기록한다. 기능 명세에는 해당 결정을 복제하지 말고 필요한 ADR과 정책을 연결한다.

## 고도화 활성 기능 명세

- [플랫폼 운영자 인증·세션·최초 비밀번호 변경](platform-operator-auth/spec.md): #275가 소유하는 독립 인증 namespace와 중앙 세션 계약
- [플랫폼 운영자 권한·재인증·고위험 명령 공통 기반](platform-operator-authorization/spec.md): #276이 소유하는 RBAC·사건 배정·일회 승인·guard 계약
- [회원 조회·계정 복구·제재 사건 관리](member-support/spec.md): #278이 소유하는 최소 회원 조회·mock 확인·복구·제재·이의 계약
- [운영자 계정·권한 관리 및 감사 이력](platform-operator-management-audit/spec.md): #282가 소유하는 단일 슈퍼관리자 기반 운영자 관리와 불변 감사 조회·보정 계약
- [플랫폼 매장 조회·제재·운영 중지 관리](admin-store/spec.md): #279가 소유하는 Store 단위 최소 조회·영향 확인·제재·불변 감사 계약
