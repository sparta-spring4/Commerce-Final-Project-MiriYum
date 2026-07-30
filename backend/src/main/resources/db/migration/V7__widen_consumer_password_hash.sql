-- 비밀번호 해시에 방식 접두사({sha256-bcrypt} 등)를 함께 저장하면서 기존 VARCHAR(72)로는
-- 길이가 부족해졌다(접두사 15자 + BCrypt 해시 60자 = 75자). V1은 이미 병합돼 수정할 수 없으므로
-- 별도 migration으로 확장한다. 나중에 다른 해시 방식으로 바꿀 여지도 함께 남긴다.
ALTER TABLE consumer_accounts
    MODIFY COLUMN password_hash VARCHAR(255) NOT NULL;
