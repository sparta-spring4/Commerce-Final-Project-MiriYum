CREATE TABLE rate_limit_windows (
    rate_limit_key VARCHAR(255) NOT NULL,
    window_expires_at DATETIME(6) NOT NULL,
    request_count INT NOT NULL,
    PRIMARY KEY (rate_limit_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
