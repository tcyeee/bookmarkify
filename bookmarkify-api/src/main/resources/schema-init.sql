-- Bookmarkify Database Schema Init
-- PostgreSQL schema: bookmarkify

CREATE SCHEMA IF NOT EXISTS bookmarkify;
SET search_path TO bookmarkify;

-- 1. sys_user
CREATE TABLE IF NOT EXISTS sys_user (
    id              VARCHAR(40)     PRIMARY KEY,
    nick_name       VARCHAR(200),
    device_id       VARCHAR(200),
    email           VARCHAR(200),
    phone           VARCHAR(20),
    password        VARCHAR(200),
    avatar_file_id  VARCHAR(40),
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER',
    update_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    disabled        BOOLEAN         NOT NULL DEFAULT FALSE,
    verified        BOOLEAN         NOT NULL DEFAULT FALSE
);

-- 2. bookmark
CREATE TABLE IF NOT EXISTS bookmark (
    id                  VARCHAR(40)     PRIMARY KEY,
    url_host            VARCHAR(200)    NOT NULL,
    url_path            VARCHAR(1000),
    url_scheme          VARCHAR(10)     NOT NULL,
    app_name            VARCHAR(100),
    title               VARCHAR(200),
    description         VARCHAR(1000),
    icon_base64         TEXT,
    maximal_logo_size   INT             NOT NULL DEFAULT 0,
    icon_padding        INT             NOT NULL DEFAULT 0,
    parse_status        VARCHAR(20)     NOT NULL DEFAULT 'LOADING',
    is_activity         BOOLEAN         NOT NULL DEFAULT FALSE,
    verify_flag         BOOLEAN         NOT NULL DEFAULT FALSE,
    parse_err_msg       TEXT,
    create_time         TIMESTAMP       NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP
);

-- 3. bookmark_user_link
CREATE TABLE IF NOT EXISTS bookmark_user_link (
    id              VARCHAR(40)     PRIMARY KEY,
    uid             VARCHAR(40)     NOT NULL,
    bookmark_id     VARCHAR(40),
    layout_node_id  VARCHAR(40)     NOT NULL,
    title           VARCHAR(200),
    description     VARCHAR(1000),
    url_full        VARCHAR(1000),
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE
);

-- 4. website_logo
CREATE TABLE IF NOT EXISTS website_logo (
    id              VARCHAR(40)     PRIMARY KEY,
    bookmark_id     VARCHAR(40)     NOT NULL,
    size            BIGINT          NOT NULL DEFAULT 0,
    height          INT             NOT NULL DEFAULT 0,
    width           INT             NOT NULL DEFAULT 0,
    suffix          VARCHAR(20),
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_og_img       BOOLEAN         NOT NULL DEFAULT FALSE
);

-- 5. bookmark_tag
CREATE TABLE IF NOT EXISTS bookmark_tag (
    id              VARCHAR(40)     PRIMARY KEY,
    name            VARCHAR(200),
    uid             VARCHAR(40),
    description     VARCHAR(1000),
    color           VARCHAR(10),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    last_modified   TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 6. bookmark_tag_link
CREATE TABLE IF NOT EXISTS bookmark_tag_link (
    id              VARCHAR(40)     PRIMARY KEY,
    tag_id          VARCHAR(40)     NOT NULL,
    bookmark_id     VARCHAR(40)     NOT NULL,
    uid             VARCHAR(40)     NOT NULL,
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE
);

-- 7. user_layout_node
CREATE TABLE IF NOT EXISTS user_layout_node (
    id              VARCHAR(40)     PRIMARY KEY,
    parent_id       VARCHAR(40),
    type            VARCHAR(30)     NOT NULL DEFAULT 'BOOKMARK',
    uid             VARCHAR(40)     NOT NULL,
    name            VARCHAR(200),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 8. bookmark_function
CREATE TABLE IF NOT EXISTS bookmark_function (
    id              VARCHAR(40)     PRIMARY KEY,
    uid             VARCHAR(40)     NOT NULL,
    layout_node_id  VARCHAR(40)     NOT NULL,
    type            VARCHAR(30)     NOT NULL,
    create_at       TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 9. user_preference
CREATE TABLE IF NOT EXISTS user_preference (
    id                      VARCHAR(40)     PRIMARY KEY,
    uid                     VARCHAR(40)     NOT NULL,
    background_config_id    VARCHAR(40),
    bookmark_open_mode      VARCHAR(30)     NOT NULL DEFAULT 'NEW_TAB',
    minimal_mode            BOOLEAN         NOT NULL DEFAULT FALSE,
    bookmark_gap            VARCHAR(30)     NOT NULL DEFAULT 'DEFAULT',
    bookmark_image_size     VARCHAR(30)     NOT NULL DEFAULT 'MEDIUM',
    show_title              BOOLEAN         NOT NULL DEFAULT TRUE,
    show_desktop_add_entry  BOOLEAN         NOT NULL DEFAULT TRUE,
    page_mode               VARCHAR(30)     NOT NULL DEFAULT 'VERTICAL_SCROLL',
    node_sort_map_json      JSON,
    update_time             TIMESTAMP       NOT NULL DEFAULT NOW(),
    create_time             TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 10. background_config
CREATE TABLE IF NOT EXISTS background_config (
    id                  VARCHAR(40)     PRIMARY KEY,
    uid                 VARCHAR(40)     NOT NULL,
    type                VARCHAR(20)     NOT NULL,
    background_link_id  VARCHAR(40)     NOT NULL,
    update_time         TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 11. background_image
CREATE TABLE IF NOT EXISTS background_image (
    id              VARCHAR(40)     PRIMARY KEY,
    uid             VARCHAR(40)     NOT NULL,
    file_id         VARCHAR(40)     NOT NULL,
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE
);

-- 12. background_gradient
CREATE TABLE IF NOT EXISTS background_gradient (
    id              VARCHAR(40)     PRIMARY KEY,
    uid             VARCHAR(40)     NOT NULL,
    name            VARCHAR(200),
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,
    gradient        TEXT            NOT NULL,
    direction       INT             NOT NULL DEFAULT 0
);

-- 13. user_file
CREATE TABLE IF NOT EXISTS user_file (
    id              VARCHAR(40)     PRIMARY KEY,
    uid             VARCHAR(40)     NOT NULL,
    origin_name     VARCHAR(500),
    size            BIGINT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    type            VARCHAR(30)     NOT NULL,
    environment     VARCHAR(30)     NOT NULL,
    current_name    VARCHAR(200)    NOT NULL,
    suffix          VARCHAR(20)     NOT NULL
);

-- 14. sms_record
CREATE TABLE IF NOT EXISTS sms_record (
    id              VARCHAR(40)     PRIMARY KEY,
    uid             VARCHAR(40)     NOT NULL,
    biz_id          VARCHAR(200),
    code            VARCHAR(50),
    message         VARCHAR(500),
    request_id      VARCHAR(200),
    content         TEXT,
    err_code        VARCHAR(100),
    phone_num       VARCHAR(20),
    create_time     TIMESTAMP,
    send_date       TIMESTAMP,
    receive_date    TIMESTAMP,
    sms_status_str  VARCHAR(50),
    sms_type_str    VARCHAR(50)
);

-- 15. home_item (legacy)
CREATE TABLE IF NOT EXISTS home_item (
    id                      VARCHAR(40)     PRIMARY KEY,
    uid                     VARCHAR(40)     NOT NULL,
    sort                    INT             NOT NULL DEFAULT 99,
    type                    VARCHAR(30)     NOT NULL,
    bookmark_user_link_id   VARCHAR(40),
    bookmark_dir_json       TEXT,
    function_id             INT,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE
);
