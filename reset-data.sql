-- ============================================================
--  重置数据：清掉所有测试订单，库存恢复初始值
--  用途：每次跑完 verify.ps1 / verify-db.ps1 之后，或想从头演示时执行
--  执行方式：
--    Get-Content reset-data.sql -Raw -Encoding utf8 |
--      & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -proot --default-character-set=utf8mb4
-- ============================================================

USE wechat_ordering;

-- 先删明细（有外键约束），再删主表
DELETE FROM order_item;
DELETE FROM orders;
ALTER TABLE orders AUTO_INCREMENT = 1;
ALTER TABLE order_item AUTO_INCREMENT = 1;

-- 菜品名称恢复（防止验证脚本中途失败留下【验证中】字样）
UPDATE dish SET name = '宫保鸡丁' WHERE id = 1;

-- 库存恢复成 init.sql 里的初始值
UPDATE dish SET stock = 100 WHERE id = 1;
UPDATE dish SET stock = 100 WHERE id = 2;
UPDATE dish SET stock = 80  WHERE id = 3;
UPDATE dish SET stock = 90  WHERE id = 4;
UPDATE dish SET stock = 100 WHERE id = 5;
UPDATE dish SET stock = 100 WHERE id = 6;
UPDATE dish SET stock = 120 WHERE id = 7;
UPDATE dish SET stock = 100 WHERE id = 8;
UPDATE dish SET stock = 150 WHERE id = 9;
UPDATE dish SET stock = 100 WHERE id = 10;
UPDATE dish SET stock = 120 WHERE id = 11;
UPDATE dish SET stock = 130 WHERE id = 12;
UPDATE dish SET stock = 200 WHERE id = 13;
UPDATE dish SET stock = 200 WHERE id = 14;
UPDATE dish SET stock = 150 WHERE id = 15;
UPDATE dish SET stock = 180 WHERE id = 16;
UPDATE dish SET stock = 120 WHERE id = 17;
UPDATE dish SET stock = 50  WHERE id = 18;
UPDATE dish SET stock = 60  WHERE id = 19;
UPDATE dish SET stock = 80  WHERE id = 20;

SELECT '数据已重置' AS result;
SELECT COUNT(*) AS orders_count FROM orders;
SELECT SUM(stock) AS total_stock FROM dish;
