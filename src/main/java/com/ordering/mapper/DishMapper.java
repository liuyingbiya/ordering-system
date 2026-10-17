package com.ordering.mapper;

import com.ordering.model.Dish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 菜品数据访问接口 —— 对应原来老项目的 DishDao。
 *
 * ============================ 阶段 2 的核心：Mapper 是什么 ============================
 *
 * 看这个接口，注意一个奇怪的地方：**它没有实现类**。
 *
 * 传统 Java 里，接口必须有类 implements 它，否则不能 new。
 * 但 MyBatis 的 Mapper 是个例外 —— 你只写接口，实现在运行时由 MyBatis「动态生成」。
 *
 * 它怎么知道方法该执行什么 SQL？靠两样东西对起来：
 *   1. 这个接口的全限定名：com.ordering.mapper.DishMapper
 *   2. XML 的 namespace：<mapper namespace="com.ordering.mapper.DishMapper">
 *      ↑ 这两个必须一模一样
 *
 *   3. 方法名 findActive
 *   4. XML 里 <select id="findActive">
 *      ↑ 这两个必须一模一样
 *
 * 所以 MyBatis 启动时会做一件事：扫描 XML，把每个 <select id="x"> 记下来，
 * 然后给每个 Mapper 接口生成一个代理对象（Proxy），
 * 你调 findActive() 时，代理就去执行 id="findActive" 对应的 SQL。
 *
 * ============================ 和老 DishDao 的对比 ============================
 *
 * 老代码（DishDao.findAll，24 行）：
 *     public List<Dish> findAll() {
 *         List<Dish> dishes = new ArrayList<>();
 *         String sql = "SELECT * FROM dish WHERE active = true ORDER BY category, id";
 *         try (Connection conn = DatabaseUtil.getConnection();
 *              PreparedStatement pstmt = conn.prepareStatement(sql);
 *              ResultSet rs = pstmt.executeQuery()) {
 *             while (rs.next()) {
 *                 Dish dish = extractDish(rs);   // ← 这一行背后是手写 9 个 setter
 *                 dishes.add(dish);
 *             }
 *         } catch (SQLException e) {
 *             e.printStackTrace();               // ← 出了问题只打日志，调用方永远不知道
 *         }
 *         return dishes;                         // ← 失败也返回空列表，静默失败
 *     }
 *
 * 新代码：
 *     List<Dish> findActive();
 *
 * 一行。SQL 在 XML 里，结果映射交给 MyBatis，连接管理交给连接池，异常交给 Spring。
 *
 * ============================ 两个必须能答上来的追问 ============================
 *
 * Q: JDBC 那套「拿连接、建 Statement、执行、遍历 ResultSet、关连接」的代码去哪了？
 * A: 都被 MyBatis 封装了。它内部还是用 JDBC，只是把模板化的部分自动做了，
 *    你只需要提供「SQL」和「怎么映射」这两件真正跟业务有关的事。
 *
 * Q: 那 MyBatis 相比直接用 JDBC 好在哪？
 * A: 1) 不用写 ResultSet 映射（手写 9 个 setter 容易漏、容易错）；
 *    2) 连接由连接池管理，不用自己 close（老代码 try-with-resources 虽然对，但每次新建连接）；
 *    3) SQL 和 Java 代码分离，改 SQL 不用重新编译 Java；
 *    4) 支持动态 SQL（if/foreach），拼条件不用写一堆字符串；
 *    5) 异常统一转换成 Spring 的 DataAccessException，不用到处 try-catch。
 */
@Mapper
public interface DishMapper {

    /**
     * 查所有上架菜品。
     * 对应老代码 DishDao.findAll()。
     * 对应 XML 里 <select id="findActive">。
     */
    List<Dish> findActive();

    /**
     * 按分类查上架菜品。
     * 对应老代码 DishDao.findByCategory(String)。
     *
     * @Param("category") 的作用：给参数起个名字，好在 XML 里用 #{category} 引用。
     *
     * 【准确的规则，别记错】
     *   - 只有一个参数时：MyBatis 会把这个参数对象直接传过去，XML 里写 #{随便什么名字}
     *     都能取到（比如 findById 就算去掉 @Param，写 #{id} 也照样能用）。
     *   - 有【多个】参数时：才必须用 @Param 起名，否则 XML 里只能用 #{param1}/#{arg0}
     *     这种位置名，很难维护。
     *   所以本项目统一加 @Param，不是「不加会报错」，而是「加了更清楚、多参数时也必须」。
     */
    List<Dish> findActiveByCategory(@Param("category") String category);

    /**
     * 按 id 查单个菜品（只查上架的）。
     * 对应老代码 DishDao.findById(Integer)。
     */
    Dish findById(@Param("id") Integer id);

    /**
     * 扣减库存。
     * 对应老代码 DishDao 里没有的方法 —— 老项目从来没减过库存，
     * 所以理论上库存 100 的菜可以无限下单。这是阶段 3 补上的业务漏洞。
     *
     * @return 受影响行数。返回 0 说明「库存不够，没扣成」
     *
     * 注意 SQL 里的 stock >= #{quantity} 这个条件 —— 见 XML 里的说明，
     * 这是一个「防止超卖」的写法。
     */
    int decreaseStock(@Param("id") Integer id, @Param("quantity") Integer quantity);

    /** 新增菜品（管理端用，暂时没做页面，先把接口留着） */
    int insert(Dish dish);

    /**
     * 软删除：把 active 改成 false，不真删数据。
     * 对应老代码 DishDao.deleteDish(Integer)。
     */
    int softDelete(@Param("id") Integer id);
}
