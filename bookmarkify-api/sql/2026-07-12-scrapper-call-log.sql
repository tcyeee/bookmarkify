-- scrapper_call_log: 记录每次 API 调用 bookmarkify-scrapper（POST /scrape）的日志
CREATE TABLE scrapper_call_log (
    id          VARCHAR(40)  NOT NULL PRIMARY KEY,
    url         VARCHAR(500) NOT NULL,
    url_host    VARCHAR(200) NOT NULL,
    success     BOOLEAN      NOT NULL,
    http_status INT,
    source      VARCHAR(20),
    cached      BOOLEAN,
    duration_ms BIGINT       NOT NULL,
    error_msg   VARCHAR(500),
    create_time TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_scrapper_call_log_url_host ON scrapper_call_log (url_host);
CREATE INDEX idx_scrapper_call_log_create_time ON scrapper_call_log (create_time);

GRANT SELECT, INSERT ON scrapper_call_log TO bookmarkify_developer;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO bookmarkify_developer;
