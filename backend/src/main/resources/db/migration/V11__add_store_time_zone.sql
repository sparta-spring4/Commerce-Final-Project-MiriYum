ALTER TABLE stores
    ADD COLUMN time_zone_id VARCHAR(64) NULL AFTER address;

UPDATE stores
SET time_zone_id = 'Asia/Seoul'
WHERE time_zone_id IS NULL;

ALTER TABLE stores
    MODIFY COLUMN time_zone_id VARCHAR(64) NOT NULL;
