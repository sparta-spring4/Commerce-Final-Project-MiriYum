-- 기존 연락처를 새 MVP 저장 형식으로 먼저 검증·정규화한다.
-- 임시 테이블의 CHECK와 계정 유형별 UNIQUE가 실패하면 migration 전체가 중단되어
-- 기존 번호 충돌이나 허용되지 않은 형식이 조용히 운영 DB에 들어가지 않는다.
CREATE TEMPORARY TABLE miriyum_contact_normalization (
    account_type VARCHAR(32) NOT NULL,
    account_id BIGINT NOT NULL,
    normalized_phone VARCHAR(512) NOT NULL,
    PRIMARY KEY (account_type, account_id),
    UNIQUE KEY uk_miriyum_contact_normalization_phone (account_type, normalized_phone),
    CONSTRAINT ck_miriyum_contact_normalization_phone
        CHECK (normalized_phone REGEXP '^010[0-9]{8}$')
);

INSERT INTO miriyum_contact_normalization (account_type, account_id, normalized_phone)
SELECT 'CONSUMER', consumer_account_id,
       REGEXP_REPLACE(phone, '[[:space:]-]', '')
FROM consumer_accounts
WHERE phone IS NOT NULL
UNION ALL
SELECT 'STORE_OPERATOR', store_operator_account_id,
       REGEXP_REPLACE(phone, '[[:space:]-]', '')
FROM store_operator_accounts
WHERE phone IS NOT NULL;

UPDATE consumer_accounts account
JOIN miriyum_contact_normalization normalized
  ON normalized.account_type = 'CONSUMER'
 AND normalized.account_id = account.consumer_account_id
SET account.phone = normalized.normalized_phone;

UPDATE store_operator_accounts account
JOIN miriyum_contact_normalization normalized
  ON normalized.account_type = 'STORE_OPERATOR'
 AND normalized.account_id = account.store_operator_account_id
SET account.phone = normalized.normalized_phone;

DROP TEMPORARY TABLE miriyum_contact_normalization;

ALTER TABLE consumer_accounts
    ADD COLUMN reservation_contact_reference VARCHAR(512) NULL;

ALTER TABLE consumer_accounts
    ADD CONSTRAINT uk_consumer_accounts_reservation_contact_reference
        UNIQUE (reservation_contact_reference);
