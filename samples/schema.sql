-- ============================================================
-- 评测 schema — 千万级电商场景
-- ============================================================
-- 8 张表覆盖: 用户 / 商家 / 分类 / 商品 / 订单 / 订单明细 / 评论 / 用户行为
-- 索引设计有意留缺口, 用于制造缺索引 / 低基数 / 复合排序等不同 pattern.
-- 真实灌入参考 samples/seed.sql, 通过 @scale 控制数据量.
-- ============================================================

DROP TABLE IF EXISTS user_actions;
DROP TABLE IF EXISTS reviews;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS merchants;
DROP TABLE IF EXISTS users;

-- ----------------------------
-- 用户表 ~500 万行
-- ----------------------------
CREATE TABLE users (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    phone           VARCHAR(20)     COMMENT '手机号(字符串)',
    email           VARCHAR(100),
    name            VARCHAR(50),
    age             INT,
    gender          TINYINT         COMMENT '0=未知 1=男 2=女',
    city            VARCHAR(50),
    status          TINYINT         DEFAULT 1   COMMENT '0=禁用 1=正常 2=注销',
    register_time   DATETIME        NOT NULL,
    last_login      DATETIME,
    INDEX idx_phone         (phone),
    INDEX idx_email         (email),
    INDEX idx_register_time (register_time)
    -- name / age / gender / city / status / last_login 无索引
) ENGINE=InnoDB COMMENT='用户 ~500万';

-- ----------------------------
-- 商家表 ~10 万行
-- ----------------------------
CREATE TABLE merchants (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL,
    contact_phone   VARCHAR(20),
    region          VARCHAR(50),
    level           TINYINT         COMMENT '商家等级 1-5',
    status          TINYINT         DEFAULT 1,
    create_time     DATETIME        NOT NULL,
    INDEX idx_region        (region),
    INDEX idx_create_time   (create_time)
    -- name / contact_phone / level / status 无索引
) ENGINE=InnoDB COMMENT='商家 ~10万';

-- ----------------------------
-- 商品分类 ~1000 行(小表,全表扫不算问题)
-- ----------------------------
CREATE TABLE categories (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL,
    parent_id       BIGINT          DEFAULT 0,
    level           TINYINT,
    INDEX idx_parent (parent_id)
) ENGINE=InnoDB COMMENT='分类 ~1000(小表)';

-- ----------------------------
-- 商品表 ~100 万行
-- ----------------------------
CREATE TABLE products (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    merchant_id     BIGINT          NOT NULL,
    category_id     BIGINT,
    name            VARCHAR(200)    NOT NULL,
    price           DECIMAL(10, 2),
    stock           INT,
    status          TINYINT         DEFAULT 1,
    create_time     DATETIME        NOT NULL,
    INDEX idx_merchant      (merchant_id),
    INDEX idx_category      (category_id),
    INDEX idx_create_time   (create_time)
    -- name / price / stock / status 无索引
) ENGINE=InnoDB COMMENT='商品 ~100万';

-- ----------------------------
-- 订单表 ~1000 万行(主力大表)
-- ----------------------------
CREATE TABLE orders (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    merchant_id     BIGINT          NOT NULL,
    status          TINYINT         NOT NULL    COMMENT '0=未支付 1=已支付 2=已发货 3=完成 4=取消',
    amount          DECIMAL(10, 2),
    order_no        VARCHAR(32),
    create_time     DATETIME        NOT NULL,
    pay_time        DATETIME,
    update_time     DATETIME,
    INDEX  idx_user_id          (user_id),
    INDEX  idx_merchant_id      (merchant_id),
    INDEX  idx_create_time      (create_time),
    INDEX  idx_status_create    (status, create_time),
    UNIQUE INDEX uk_order_no    (order_no)
    -- amount / pay_time / update_time 无索引
) ENGINE=InnoDB COMMENT='订单 ~1000万';

-- ----------------------------
-- 订单明细 ~2000 万行
-- ----------------------------
CREATE TABLE order_items (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    order_id        BIGINT          NOT NULL,
    product_id      BIGINT          NOT NULL,
    quantity        INT             NOT NULL,
    price           DECIMAL(10, 2),
    INDEX idx_order_id      (order_id),
    INDEX idx_product_id    (product_id)
    -- quantity / price 无索引
) ENGINE=InnoDB COMMENT='订单明细 ~2000万';

-- ----------------------------
-- 评论表 ~300 万行
-- ----------------------------
CREATE TABLE reviews (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    product_id      BIGINT          NOT NULL,
    order_id        BIGINT,
    rating          TINYINT         COMMENT '1-5 星',
    content         TEXT,
    create_time     DATETIME        NOT NULL,
    INDEX idx_product_create    (product_id, create_time),
    INDEX idx_user              (user_id)
    -- order_id / rating 无索引
) ENGINE=InnoDB COMMENT='评论 ~300万';

-- ----------------------------
-- 用户行为日志 ~1500 万行
-- ----------------------------
CREATE TABLE user_actions (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    action_type     VARCHAR(20)     COMMENT 'view/click/cart/favorite',
    target_id       BIGINT          COMMENT '商品 ID 或其他实体 ID',
    target_type     VARCHAR(20)     COMMENT 'product/category/merchant',
    create_time     DATETIME        NOT NULL,
    INDEX idx_user_time     (user_id, create_time)
    -- action_type / target_id / target_type 无索引(故意为"缺索引"测试预留)
) ENGINE=InnoDB COMMENT='用户行为 ~1500万';
