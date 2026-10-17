package com.ordering.service;

import com.ordering.mapper.DishMapper;
import com.ordering.mapper.OrderMapper;
import com.ordering.model.Cart;
import com.ordering.model.CartItem;
import com.ordering.model.Order;
import com.ordering.model.OrderItem;
import com.ordering.util.OrderNumberGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单业务层。
 *
 * ============================ 阶段 1 到现在的变化 ============================
 *
 * 阶段 1：订单存在 ConcurrentHashMap 里，服务一重启全没。
 * 现在：订单真的写进 MySQL 的 orders 和 order_item 表，重启也在。
 *
 * ============================ 本类最重要的知识点：@Transactional ============================
 *
 * 看 createOrder() 方法上的 @Transactional 注解，这一行是整个类里最值钱的。
 *
 * 【问题场景】下单这个动作涉及的数据变更：
 *   ① INSERT orders（订单主表）
 *   ② INSERT order_item × N（订单明细）
 *   ③ UPDATE dish SET stock = stock - n × N（扣库存）
 *   （④ 清空购物车在 Controller 里做，而且它不在数据库事务范围内 —— 见下面说明）
 *
 * 如果做到第 ③ 步时数据库挂了 / 库存不够 / 抛了异常，会怎样？
 *   没有事务：① ② 已经写进去了，③ 只完成一半 —— 数据库里出现一张
 *            「有订单、有明细、但库存没扣」的脏数据。这是严重的数据不一致。
 *   有事务：  ①②③ 要么全部成功，要么全部回滚，不会出现「做了一半」的状态。
 *
 * ⚠️ 一个精确性问题（别讲错）：**清空购物车不在事务里**。
 *   购物车存在 session（服务器内存）中，不是数据库表，Spring 的事务管不到它。
 *   所以严格说，事务保护的是「① ② ③ 三件数据库操作」。
 *   这也是为什么阶段 2 没把购物车落库的原因之一 —— 一旦它变成第 4 张表，
 *   清空购物车也应该纳入事务。
 *   面试被问「你的事务范围是什么」，答「管住了订单主表、明细、库存三处数据库写入，
 *   session 里的购物车不在事务范围内」，比笼统说「整个下单流程都在事务里」严谨。
 *
 * 【事务的四个特性 ACID】（面试必背，但你要知道每个词在你项目里对应什么）
 *   A 原子性 Atomicity   —— 上面 ①②③ 是一个整体，要么全做要么全不做（@Transactional 保证）
 *   C 一致性 Consistency —— 订单总价 = 各明细小计之和（业务规则，代码保证）
 *   I 隔离性 Isolation   —— 两个用户同时下单互不干扰（数据库隔离级别保证，MySQL 默认 REPEATABLE READ）
 *   D 持久性 Durability  —— 提交后即使断电也不丢（InnoDB 的 redo log 保证）
 *
 * 【@Transactional 是怎么生效的？—— 这个追问很常见】
 *   不是靠「魔法」，是靠 Spring AOP 生成代理对象。
 *   Spring 发现某个 Bean 的方法上有 @Transactional，就给这个 Bean 生成一个代理，
 *   你调方法时其实调的是代理：代理先开启事务 → 调真实方法 → 成功则提交，异常则回滚。
 *
 *   ⚠️ 由此推出三个经典的「事务失效」场景（面试高频）：
 *     1. 方法不是 public —— 代理拦不到（private 一定不行）
 *     2. 同一个类里自调用（this.method()）—— 走的是原始对象，没经过代理
 *     3. 异常被 catch 住没抛出 —— 事务管理器看不到异常，不会回滚
 *   这三个你能说清楚，说明你真的懂事务，不是背注解。
 */
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private DishMapper dishMapper;

    /**
     * 下单。
     *
     * @param cart        购物车
     * @param userId      用户标识
     * @param userName    收货人
     * @param userPhone   电话
     * @param userAddress 地址
     * @return 创建好的订单（id 和 orderNumber 已填充）
     * @throws IllegalStateException 库存不足时抛出，触发事务回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(Cart cart, String userId, String userName,
                             String userPhone, String userAddress) {

        Order order = new Order();
        order.setOrderNumber(OrderNumberGenerator.generate());
        order.setUserId(userId);
        order.setUserName(userName);
        order.setUserPhone(userPhone);
        order.setUserAddress(userAddress);
        order.setTotalPrice(cart.getTotalPrice());
        order.setStatus(Order.STATUS_PENDING);

        // ① 插入订单主表。插入后 order.getId() 会被自动填上（XML 里的 useGeneratedKeys）
        orderMapper.insert(order);

        // ② 组装明细，同时 ③ 扣库存
        for (CartItem cartItem : cart.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());       // 这里用到 ① 回填的 id
            orderItem.setDishId(cartItem.getDish().getId());
            orderItem.setDishName(cartItem.getDish().getName());
            orderItem.setPrice(cartItem.getDish().getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setSubtotal(cartItem.getSubtotal());
            order.addItem(orderItem);

            // 扣库存。返回 0 说明库存不足（SQL 里 WHERE stock >= quantity 没匹配到）
            int affected = dishMapper.decreaseStock(
                    cartItem.getDish().getId(), cartItem.getQuantity());
            if (affected == 0) {
                // 抛异常 → @Transactional 自动回滚 → 前面已经插入的订单主表也会被撤销
                throw new IllegalStateException(
                        "库存不足：" + cartItem.getDish().getName());
            }
        }

        // 批量插入所有明细（一条 INSERT 插多行）
        orderMapper.insertItems(order.getItems());

        return order;
    }

    public Order getOrderById(Integer id) {
        if (id == null) {
            return null;
        }
        return orderMapper.findById(id);
    }

    public List<Order> getOrdersByUserId(String userId) {
        if (userId == null) {
            return java.util.Collections.emptyList();
        }
        return orderMapper.findByUserId(userId);
    }

    public List<Order> getAllOrders() {
        return orderMapper.findAll();
    }

    /**
     * 改订单状态 —— 带「状态机」校验。
     *
     * ============================ 为什么需要状态机 ============================
     *
     * 最初写法：
     *     public boolean updateStatus(Integer orderId, Integer status) {
     *         return orderMapper.updateStatus(orderId, status) > 0;
     *     }
     *
     * 问题：传什么状态就写什么状态。于是：
     *   - 传 -1 或 99 → 数据库里存了非法状态，页面 getStatusText() 显示「未知状态」
     *   - 已完成的订单能被改回「已确认」→ 业务数据错乱
     *   - 已取消的订单能「复活」成「已确认」→ 更离谱
     *
     * 【什么是状态机】
     * 订单状态不是可以随便跳的，它有合法路径：
     *
     *     待确认(0) ──→ 已确认(1) ──→ 制作中(2) ──→ 已配送(3) ──→ 已完成(4)
     *         │
     *         └──→ 已取消(5)
     *
     * 只能在上面这些箭头里跳。比如「待确认」不能直接跳到「已完成」，
     * 「已完成」是终态，不能改回任何其他状态。
     *
     * 把这个规则写在代码里（而不是靠页面只显示对的按钮），
     * 是因为**页面可以被绕过**——有人直接 POST /order/5/status?status=1 就绕过了页面的按钮限制。
     * 又是那句话：前端校验不可信，服务端必须校验。
     *
     * 这个点在面试里很好讲：「订单状态我做了状态机校验，只允许合法的状态迁移，
     * 防止绕过前端直接调接口把订单改成非法状态。」
     */
    public boolean updateStatus(Integer orderId, Integer status) {
        if (orderId == null || status == null) {
            return false;
        }
        // ① 状态值必须在合法范围内
        if (status < Order.STATUS_PENDING || status > Order.STATUS_CANCELLED) {
            return false;
        }

        Order order = orderMapper.findById(orderId);
        if (order == null) {
            return false;
        }

        // ② 只允许合法的状态迁移
        Integer current = order.getStatus() == null ? Order.STATUS_PENDING : order.getStatus();
        if (!isValidTransition(current, status)) {
            return false;
        }

        return orderMapper.updateStatus(orderId, status) > 0;
    }

    /**
     * 判断「从 from 状态能不能迁移到 to 状态」。
     *
     * 写成一个独立的小方法，而不是塞在 updateStatus 里，有两个好处：
     *   1. 规则集中在一个地方，以后要改状态流程只改这里
     *   2. 可以单独写单元测试验证它（比如断言「已完成不能改回已确认」）
     */
    private boolean isValidTransition(int from, int to) {
        switch (from) {
            case Order.STATUS_PENDING:      // 待确认 → 已确认 / 已取消
                return to == Order.STATUS_CONFIRMED || to == Order.STATUS_CANCELLED;
            case Order.STATUS_CONFIRMED:    // 已确认 → 制作中 / 已取消
                return to == Order.STATUS_PREPARING || to == Order.STATUS_CANCELLED;
            case Order.STATUS_PREPARING:    // 制作中 → 已配送
                return to == Order.STATUS_DELIVERED;
            case Order.STATUS_DELIVERED:    // 已配送 → 已完成
                return to == Order.STATUS_COMPLETED;
            case Order.STATUS_COMPLETED:    // 已完成是终态，不能再改
                return false;
            case Order.STATUS_CANCELLED:    // 已取消也是终态
                return false;
            default:
                return false;
        }
    }

    /**
     * 取消订单 —— 带「归属校验」。
     *
     * ============================ 修复越权操作（IDOR） ============================
     *
     * 最初写法只判断「订单存在 + 状态是待确认」，没判断「这个订单是不是你的」。
     * 于是任何人知道订单 id，就能 POST /order/5/cancel 取消别人的订单，
     * 这叫「不安全的直接对象引用」（IDOR, Insecure Direct Object Reference），
     * 是 OWASP Top 10 里的常客。
     *
     * 修法：把当前用户的 userId 传进来，比对订单的 userId。
     *
     * 注意：因为我没做登录，userId 是 "guest_<sessionId>"，
     * 所以严格说这个校验只是「不同浏览器的 session 不能互相操作」，
     * 挡不住「同一个人开两个标签页」。但这已经是当前架构下能做到的最好了，
     * 真要做对必须先有登录。**面试被问「你的权限控制怎么做的」，
     * 要如实说：目前只有基于 session 的归属校验，没有真正的认证授权，
     * 这是我已知的缺口。** 别夸大。
     *
     * @param orderId       订单 id
     * @param currentUserId 当前请求所属用户的 userId
     */
    public boolean cancelOrder(Integer orderId, String currentUserId) {
        if (orderId == null || currentUserId == null) {
            return false;
        }
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            return false;
        }

        // ★ 归属校验：不是你的订单，不能取消
        if (!currentUserId.equals(order.getUserId())) {
            return false;
        }

        if (!Integer.valueOf(Order.STATUS_PENDING).equals(order.getStatus())) {
            return false;
        }
        return orderMapper.updateStatus(orderId, Order.STATUS_CANCELLED) > 0;
    }
}
