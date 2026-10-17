package com.ordering.mapper;

import com.ordering.model.Order;
import com.ordering.model.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单数据访问接口 —— 对应原来老项目的 OrderDao。
 *
 * ============================ 和老 OrderDao 的关键差别 ============================
 *
 * 老 OrderDao.createOrder() 里有一段很典型的「手动处理自增主键」代码：
 *
 *     try (PreparedStatement pstmt = conn.prepareStatement(sql,
 *              PreparedStatement.RETURN_GENERATED_KEYS)) {     // ← 要求返回自增主键
 *         ...
 *         try (ResultSet rs = pstmt.getGeneratedKeys()) {
 *             if (rs.next()) {
 *                 order.setId(rs.getInt(1));                   // ← 手动读出来塞回对象
 *                 for (OrderItem item : order.getItems()) {
 *                     saveOrderItem(conn, order.getId(), item); // ← 手动传 conn 保证同一事务
 *                 }
 *             }
 *         }
 *     }
 *
 * 注意 saveOrderItem(conn, ...) 为什么要把 conn 传进去？
 * 真正的答案（别记错）：因为要在同一个 Connection 上执行，省得每次重新建连接。
 * 但它【并没有保证事务】—— 老代码全程没有 setAutoCommit(false)、也没有 commit()，
 * 而 MySQL 的 Connection 默认 autoCommit = true，也就是每条语句各自提交。
 * 所以老代码里「插订单主表成功、插明细失败」时，主表那条是留着的，是脏数据。
 *
 * ⚠️ 这个点很容易被误解成「传 conn 就是为了事务」—— 很多人（包括我一开始）
 *    都这么以为。面试官如果顺着追问「那它的 autoCommit 是什么」，就会很难看。
 *    记住：**同一个 Connection ≠ 同一个事务**，事务需要显式开启（setAutoCommit(false)
 *    或交给 Spring 的 @Transactional）。
 *
 * MyBatis + Spring 的写法（见本类 insert 方法的 XML）：
 *     <insert id="insert" useGeneratedKeys="true" keyProperty="id">
 * 一个属性就搞定了「插入后把自增 id 回填到对象的 id 字段」，
 * 事务则由 Service 层（OrderService.createOrder）上的 @Transactional 保证。
 */
@Mapper
public interface OrderMapper {

    /**
     * 插入订单主表。
     * ★ useGeneratedKeys + keyProperty 见 XML：插入完成后，
     *   数据库生成的自增 id 会自动写回 order.getId()。
     * @return 受影响行数
     */
    int insert(Order order);

    /**
     * 批量插入订单明细。
     * 用 foreach 生成多组 VALUES，一次 INSERT 插多条，比循环调 N 次快得多。
     */
    int insertItems(@Param("items") List<OrderItem> items);

    /** 按 id 查订单（含明细，靠 XML 里的 collection 映射一次性查出来） */
    Order findById(@Param("id") Integer id);

    /** 按用户查订单，按时间倒序 */
    List<Order> findByUserId(@Param("userId") String userId);

    /** 查全部订单 */
    List<Order> findAll();

    /** 改状态 */
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    /** 按订单号查（下单时用来检查有没有重复） */
    Order findByOrderNumber(@Param("orderNumber") String orderNumber);
}
