-- 强制本次导入使用 UTF-8。
-- 不加这一行时，MySQL 容器里的 mysql 客户端在 C locale 下会用 latin1 连接，
-- 导致脚本里的中文写进库变成乱码（这个坑很隐蔽，因为表结构和英文数据都正常）。
SET NAMES utf8mb4;

-- ============================================================
--  建库脚本（自包含版）
--
--  ★ 为什么不用原项目的 database/init.sql？
--    因为原项目那份少了 icon 列，而现在的 Mapper 会查 icon，
--    直接导原脚本会导致所有菜品查询报 Unknown column 'icon'。
--    这个文件是修正后的完整脚本，全新环境只需要跑这一个。
--
--  执行方式：
--    Get-Content build-database.sql -Raw -Encoding utf8 |
--      & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -proot --default-character-set=utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS wechat_ordering
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE wechat_ordering;

-- ------------------------------------------------------------
-- 菜品表
-- 注意 icon 列：这是阶段 3 新增的。
-- 原本图标是写死在 Java 里的（Dish 构造函数第 6 个参数），数据进库后图标也得有地方存。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS dish;

CREATE TABLE dish (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL COMMENT '菜品名称',
    description VARCHAR(500)  COMMENT '菜品描述',
    price       DECIMAL(10,2) NOT NULL COMMENT '价格',
    category    VARCHAR(50)   NOT NULL COMMENT '分类',
    image_url   VARCHAR(255)  COMMENT '图片URL',
    icon        VARCHAR(32)   DEFAULT NULL COMMENT '页面展示图标',
    stock       INT           DEFAULT 100 COMMENT '库存',
    create_time TIMESTAMP     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    active      BOOLEAN       DEFAULT TRUE COMMENT '是否上架（软删除用）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品表';

-- ------------------------------------------------------------
-- 订单表
-- ★ order_number 上的 UNIQUE 不是可选的：
--   订单号算法是「秒级时间 + 4 位随机数」，同一秒并发下单理论上有极小概率撞号。
--   有这个约束，撞号时数据库直接拒绝 → 事务回滚 → 不会产生两张同号订单。
-- ------------------------------------------------------------
CREATE TABLE orders (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    order_number VARCHAR(50)   NOT NULL UNIQUE COMMENT '订单号',
    user_id      VARCHAR(100)  NOT NULL COMMENT '用户ID',
    user_name    VARCHAR(50)   NOT NULL COMMENT '收货人姓名',
    user_phone   VARCHAR(20)   NOT NULL COMMENT '联系电话',
    user_address VARCHAR(255)  NOT NULL COMMENT '送餐地址',
    total_price  DECIMAL(10,2) NOT NULL COMMENT '订单总价',
    status       INT           DEFAULT 0 COMMENT '0待确认 1已确认 2制作中 3已配送 4已完成 5已取消',
    create_time  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ------------------------------------------------------------
-- 订单明细表
-- ON DELETE CASCADE：删订单时明细自动跟着删，不会留孤儿数据
-- ------------------------------------------------------------
CREATE TABLE order_item (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    order_id  INT           NOT NULL COMMENT '订单ID',
    dish_id   INT           NOT NULL COMMENT '菜品ID',
    dish_name VARCHAR(100)  NOT NULL COMMENT '下单时的菜名（冗余保存，不随菜品改名而变）',
    price     DECIMAL(10,2) NOT NULL COMMENT '下单时的单价',
    quantity  INT           NOT NULL COMMENT '数量',
    subtotal  DECIMAL(10,2) NOT NULL COMMENT '小计',
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- ------------------------------------------------------------
-- 初始化 20 条菜品（含 icon）
-- ------------------------------------------------------------
INSERT INTO dish (name, description, price, category, icon, stock) VALUES
('宫保鸡丁',   '经典川菜，鸡丁香嫩，花生酥脆', 38.00, '主食', '🍗', 100),
('麻婆豆腐',   '正宗川味，麻辣鲜香',           28.00, '主食', '🧈', 100),
('红烧肉',     '五花肉慢炖，肥而不腻',         48.00, '主食', '🍖', 80),
('糖醋里脊',   '酸甜可口，外酥里嫩',           36.00, '主食', '🍤', 90),
('鱼香肉丝',   '经典川菜，咸甜酸辣兼备',       32.00, '主食', '🥢', 100),
('青椒肉丝',   '家常小炒，简单美味',           26.00, '主食', '🌶️', 100),
('酸辣土豆丝', '爽脆开胃，下饭神器',           18.00, '主食', '🥔', 120),
('西红柿炒蛋', '经典家常菜，营养丰富',         22.00, '主食', '🍅', 100),
('薯条',       '金黄酥脆，经典小吃',           16.00, '小吃', '🍟', 150),
('炸鸡腿',     '外酥里嫩，香气四溢',           20.00, '小吃', '🍗', 100),
('洋葱圈',     '酥脆可口',                     18.00, '小吃', '🧅', 120),
('鸡米花',     '小块鸡肉，外酥里嫩',           22.00, '小吃', '🍿', 130),
('可乐',       '冰镇可乐，清爽解渴',            6.00, '饮品', '🥤', 200),
('雪碧',       '柠檬味汽水',                    6.00, '饮品', '🥤', 200),
('橙汁',       '鲜榨橙汁',                     12.00, '饮品', '🍊', 150),
('柠檬水',     '清爽柠檬水',                   10.00, '饮品', '🍋', 180),
('奶茶',       '经典奶茶',                     15.00, '饮品', '🧋', 120),
('提拉米苏',   '意式甜点，绵密可口',           32.00, '甜点', '🍰', 50),
('芒果布丁',   '香甜芒果，嫩滑布丁',           18.00, '甜点', '🍮', 60),
('冰淇淋',     '香草/巧克力可选',              15.00, '甜点', '🍦', 80);

SELECT '建库完成' AS result;
SELECT CONCAT('菜品数: ', COUNT(*)) AS dish_count, CONCAT('总库存: ', SUM(stock)) AS total_stock FROM dish;
