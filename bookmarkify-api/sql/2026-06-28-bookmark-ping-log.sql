-- bookmark_ping_log: 记录对 CLOSED 书签的定时 ping 重试记录
CREATE TABLE bookmark_ping_log (
    id          VARCHAR(40)  NOT NULL PRIMARY KEY,
    bookmark_id VARCHAR(40)  NOT NULL,
    url_host    VARCHAR(200) NOT NULL,
    alive       BOOLEAN      NOT NULL,
    triggered_parse BOOLEAN  NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ping_log_bookmark_id ON bookmark_ping_log (bookmark_id);
CREATE INDEX idx_ping_log_create_time ON bookmark_ping_log (create_time);

GRANT SELECT, INSERT ON bookmark_ping_log TO bookmarkify_developer;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO bookmarkify_developer;
