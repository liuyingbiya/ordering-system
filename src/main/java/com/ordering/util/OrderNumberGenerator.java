package com.ordering.util;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成 + 价格/日期格式化。
 *
 * ============================ 阶段 3 修掉的线程安全问题 ============================
 *
 * 【问题是什么】
 * 老代码（以及阶段 1 我原样搬过来的版本）是这样写的：
 *
 *     private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
 *     public static String generate() {
 *         return sdf.format(new Date()) + randomNum;
 *     }
 *
 * 问题出在 `static` 上：sdf 是一个所有线程共享的实例。
 * 而 SimpleDateFormat 内部有一个 Calendar 字段用来存中间状态（年、月、日…），
 * format() 的过程是「先 set 各个字段，再输出」。
 *
 * 两个线程同时调 format()：
 *   线程 A 刚 set 完「年=2026」，还没 set「月」
 *   线程 B 把「年」改成了 2027
 *   线程 A 继续 set「月」，输出 —— 结果拿到 2027 年的订单号
 *
 * 换句话说：单线程测试永远正常，一上线并发就出脏数据。
 * 这就是「线程不安全」的典型表现，也是最难测出来的 bug 类型。
 *
 * 【怎么修的】
 * 换成 java.time.format.DateTimeFormatter。它在 Java 8 引入，**设计上就是不可变的**，
 * 所以可以安全地作为 static 常量共享，多个线程同时调用没有问题。
 *
 * 这是你面试可以讲的一个具体例子：
 *   「我项目里原来用 static SimpleDateFormat 生成订单号，
 *    这在 Servlet 多线程环境下是线程不安全的，我改成了 DateTimeFormatter。」
 *
 * 【另一个改动：Math.random() → ThreadLocalRandom】
 * Math.random() 内部用的也是共享的 Random 实例，高并发下有争用，性能不如
 * ThreadLocalRandom（每个线程一个独立随机源，不用加锁）。
 * 这个改动影响不大，但顺手改掉更专业。
 */
public class OrderNumberGenerator {

    /**
     * ★ 线程安全的日期格式化器。
     * DateTimeFormatter 是不可变对象（immutable），所以 static 共享是安全的。
     * 对比上面注释里那个 SimpleDateFormat —— 一字之差，安全性完全不同。
     */
    private static final DateTimeFormatter ORDER_NO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 日期时间格式化器（给页面显示用，同样线程安全） */
    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 订单号 = 年月日时分秒 + 4 位随机数，共 18 位。
     *
     * 例：202610171430251234
     *
     * ⚠️ 还有一个隐患要说清楚（面试官可能会追问「这样够不够唯一」）：
     * 同一秒内如果并发下单，随机 4 位只有 9000 种可能，理论上会撞。
     * 真正的解法有三种，本项目用第 3 种兜底：
     *   1. 用雪花算法（Snowflake）生成全局唯一 ID
     *   2. 用数据库序列 / Redis 自增
     *   3. 数据库表上给 order_number 加 UNIQUE 约束，撞了就插入失败并重试
     * 我们的 orders.order_number 有 UNIQUE 约束（见 init.sql），
     * 所以真的撞了会抛异常、@Transactional 回滚，不会产生重复订单。
     *
     * 知道「现在这个方案在什么情况下会出问题、怎么彻底解决」，
     * 比写出一个看起来完美的实现更重要。
     */
    public static String generate() {
        String dateStr = LocalDateTime.now().format(ORDER_NO_FORMATTER);
        int randomNum = ThreadLocalRandom.current().nextInt(1000, 10000);
        return dateStr + randomNum;
    }

    /** 金额格式化：统一保留两位小数，null 当 0 处理 */
    public static String formatPrice(BigDecimal price) {
        if (price == null) {
            return "0.00";
        }
        return String.format("%.2f", price);
    }

    /**
     * 日期格式化。
     *
     * 注意：这里保留了带 Date 参数的方法，因为 java.util.Date 在 model 里还在用。
     * 但方法内部不再 new SimpleDateFormat，而是把 Date 转成 LocalDateTime 再格式化。
     *
     * 其实更好的是把 model 里的 Date 全换成 LocalDateTime —— 这是 Java 8 之后的标准做法。
     * 为什么这次没换？因为 MyBatis 对 Date 的映射是开箱即用的，
     * 换成本地日期时间要配 TypeHandler，属于「为了改而改」。
     * 简历项目里，能不必要就不动 —— 改动越少，你越讲得清楚。
     */
    public static String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        return date.toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DISPLAY_FORMATTER);
    }
}
