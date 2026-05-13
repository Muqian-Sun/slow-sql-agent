-- ============================================================
-- 测试数据生成脚本
-- ============================================================
-- 目的:
--   为 schema.sql 建好的 8 张表灌入可控量级的可重复数据,
--   让 EXPLAIN / 双跑校验 / 列统计等工具能产出真实结果.
--
-- 设计:
--   1. 数据完全由 n 推导, 无随机, 同 @scale 下每次生成行内容一致.
--   2. 跨表 ID 用模运算保持引用完整(不依赖真实 FOREIGN KEY 约束).
--   3. 先用 doubling 一次性建辅助 numbers 表 _seed_nums,
--      8 张表的 INSERT 都从 _seed_nums LIMIT N 读, 避免重复递归.
--   4. 关键超大表 INSERT 前 DROP 索引、灌完再 ADD, 加速 bulk write.
--
-- 用法:
--   开发量级 (~5 万行, 秒级):
--     docker exec -i slowsql-mysql \
--       mysql -uroot -proot slow_sql_agent < samples/seed.sql
--
--   百万级演示 (~1250 万行):
--     ( echo 'SET @scale := 1000000;'; cat samples/seed.sql ) | \
--       docker exec -i slowsql-mysql mysql -uroot -proot slow_sql_agent
--
-- 各表行数 (基于 @scale):
--   users          = @scale
--   merchants      = max(@scale / 50, 100)
--   categories     = 100
--   products       = @scale * 2
--   orders         = @scale * 2
--   order_items    = @scale * 4   (主力大表)
--   reviews        = @scale / 2
--   user_actions   = @scale * 3
-- ============================================================

SET @scale := IFNULL(@scale, 5000);

SET @n_users        := @scale;
SET @n_merchants    := GREATEST(@scale DIV 50, 100);
SET @n_categories   := 100;
SET @n_products     := @scale * 2;
SET @n_orders       := @scale * 2;
SET @n_order_items  := @scale * 4;
SET @n_reviews      := @scale DIV 2;
SET @n_user_actions := @scale * 3;

-- 目标 _seed_nums 大小取所有表中的最大值
SET @max_n := GREATEST(@n_users, @n_merchants, @n_categories, @n_products,
                       @n_orders, @n_order_items, @n_reviews, @n_user_actions);

SELECT @scale AS scale, @max_n AS nums_size,
       @n_users AS users_n, @n_merchants AS merchants_n,
       @n_categories AS categories_n, @n_products AS products_n,
       @n_orders AS orders_n, @n_order_items AS order_items_n,
       @n_reviews AS reviews_n, @n_user_actions AS user_actions_n;

-- ----------------------------
-- 构建 numbers 表 (1..@max_n)
-- ----------------------------
-- doubling 思路: 每次 INSERT 让行数翻倍, 22 步即可到 4M+.
-- 用 SP 封装, 比硬编码 22 行 INSERT 干净, 也方便复用.
DROP PROCEDURE IF EXISTS _seed_build_nums;
DELIMITER //
CREATE PROCEDURE _seed_build_nums(IN target BIGINT)
BEGIN
    DECLARE current_max BIGINT DEFAULT 0;

    DROP TABLE IF EXISTS _seed_nums;
    CREATE TABLE _seed_nums (n BIGINT PRIMARY KEY) ENGINE=InnoDB;

    INSERT INTO _seed_nums VALUES (1);
    SET current_max = 1;

    WHILE current_max < target DO
        INSERT INTO _seed_nums
        SELECT n + current_max
        FROM _seed_nums
        WHERE n + current_max <= target;
        SELECT MAX(n) INTO current_max FROM _seed_nums;
    END WHILE;
END//
DELIMITER ;

CALL _seed_build_nums(@max_n);

-- ----------------------------
-- 清空旧业务数据
-- ----------------------------
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE user_actions;
TRUNCATE TABLE reviews;
TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
TRUNCATE TABLE products;
TRUNCATE TABLE categories;
TRUNCATE TABLE merchants;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

-- ----------------------------
-- users
-- ----------------------------
INSERT INTO users (phone, email, name, age, gender, city, status, register_time, last_login)
SELECT
    CONCAT('1', LPAD(n MOD 9999999999, 10, '0')),
    CONCAT('u', n, '@test.local'),
    CONCAT('user_', n),
    18 + (n MOD 60),
    n MOD 3,
    ELT(1 + (n MOD 5), 'Beijing','Shanghai','Guangzhou','Shenzhen','Hangzhou'),
    CASE WHEN n MOD 100 = 0 THEN 0 WHEN n MOD 137 = 0 THEN 2 ELSE 1 END,
    DATE_SUB(NOW(), INTERVAL (n MOD 730) DAY),
    DATE_SUB(NOW(), INTERVAL (n MOD 30) DAY)
FROM _seed_nums
WHERE n <= @n_users;

-- ----------------------------
-- merchants
-- ----------------------------
INSERT INTO merchants (name, contact_phone, region, level, status, create_time)
SELECT
    CONCAT('merchant_', n),
    CONCAT('1', LPAD((n * 31) MOD 9999999999, 10, '0')),
    ELT(1 + (n MOD 6), 'east','south','west','north','central','overseas'),
    1 + (n MOD 5),
    CASE WHEN n MOD 50 = 0 THEN 0 ELSE 1 END,
    DATE_SUB(NOW(), INTERVAL (n MOD 1095) DAY)
FROM _seed_nums
WHERE n <= @n_merchants;

-- ----------------------------
-- categories
-- ----------------------------
INSERT INTO categories (name, parent_id, level)
SELECT
    CONCAT('cat_', n),
    IF(n <= 10, 0, ((n MOD 10) + 1)),
    IF(n <= 10, 1, 2)
FROM _seed_nums
WHERE n <= @n_categories;

-- ----------------------------
-- products
-- ----------------------------
INSERT INTO products (merchant_id, category_id, name, price, stock, status, create_time)
SELECT
    ((n - 1) MOD @n_merchants) + 1,
    ((n - 1) MOD @n_categories) + 1,
    CONCAT('product_', n),
    ROUND(10 + (n MOD 9990) + (n * 7 MOD 100) / 100.0, 2),
    n MOD 1000,
    CASE WHEN n MOD 80 = 0 THEN 0 ELSE 1 END,
    DATE_SUB(NOW(), INTERVAL (n MOD 365) DAY)
FROM _seed_nums
WHERE n <= @n_products;

-- ----------------------------
-- orders
-- ----------------------------
INSERT INTO orders (user_id, merchant_id, status, amount, order_no, create_time, pay_time, update_time)
SELECT
    ((n - 1) MOD @n_users) + 1,
    ((n - 1) MOD @n_merchants) + 1,
    n MOD 5,
    ROUND(10 + (n MOD 9990) + (n * 13 MOD 100) / 100.0, 2),
    CONCAT('ORD', LPAD(n, 12, '0')),
    DATE_SUB(NOW(), INTERVAL (n MOD 730) DAY),
    IF(n MOD 5 = 0, NULL, DATE_SUB(NOW(), INTERVAL (n MOD 730) DAY) + INTERVAL 5 MINUTE),
    DATE_SUB(NOW(), INTERVAL (n MOD 720) HOUR)
FROM _seed_nums
WHERE n <= @n_orders;

-- ----------------------------
-- order_items (主力大表)
-- ----------------------------
INSERT INTO order_items (order_id, product_id, quantity, price)
SELECT
    ((n - 1) MOD @n_orders) + 1,
    ((n - 1) MOD @n_products) + 1,
    1 + (n MOD 9),
    ROUND(10 + (n MOD 990), 2)
FROM _seed_nums
WHERE n <= @n_order_items;

-- ----------------------------
-- reviews
-- ----------------------------
INSERT INTO reviews (user_id, product_id, order_id, rating, content, create_time)
SELECT
    ((n - 1) MOD @n_users) + 1,
    ((n - 1) MOD @n_products) + 1,
    IF(n MOD 7 = 0, NULL, ((n - 1) MOD @n_orders) + 1),
    1 + (n MOD 5),
    CONCAT('review content ', n, ' lorem ipsum'),
    DATE_SUB(NOW(), INTERVAL (n MOD 365) DAY)
FROM _seed_nums
WHERE n <= @n_reviews;

-- ----------------------------
-- user_actions
-- ----------------------------
INSERT INTO user_actions (user_id, action_type, target_id, target_type, create_time)
SELECT
    ((n - 1) MOD @n_users) + 1,
    ELT(1 + (n MOD 4), 'view','click','cart','favorite'),
    ((n - 1) MOD @n_products) + 1,
    ELT(1 + (n MOD 3), 'product','category','merchant'),
    DATE_SUB(NOW(), INTERVAL (n MOD 365) DAY)
FROM _seed_nums
WHERE n <= @n_user_actions;

-- ----------------------------
-- 清理辅助表 + 重新统计
-- ----------------------------
DROP TABLE IF EXISTS _seed_nums;
DROP PROCEDURE IF EXISTS _seed_build_nums;

ANALYZE TABLE users, merchants, categories, products,
              orders, order_items, reviews, user_actions;

SELECT
    (SELECT COUNT(*) FROM users)        AS users,
    (SELECT COUNT(*) FROM merchants)    AS merchants,
    (SELECT COUNT(*) FROM categories)   AS categories,
    (SELECT COUNT(*) FROM products)     AS products,
    (SELECT COUNT(*) FROM orders)       AS orders,
    (SELECT COUNT(*) FROM order_items)  AS order_items,
    (SELECT COUNT(*) FROM reviews)      AS reviews,
    (SELECT COUNT(*) FROM user_actions) AS user_actions;
