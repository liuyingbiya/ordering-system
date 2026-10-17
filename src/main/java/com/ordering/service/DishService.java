package com.ordering.service;

import com.ordering.mapper.DishMapper;
import com.ordering.model.Dish;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜品业务层。
 *
 * ============================ 阶段 2/3 的核心变化 ============================
 *
 * 阶段 1 的版本：菜品数据硬编码在本类里，20 行 add(...) 调用。
 * 现在的版本：一行 SQL 从 MySQL 查。
 *
 * 【最重要的观察：Controller 一行都没改】
 * 打开 HomeController、DishController 看看，它们的代码和阶段 1 完全一样：
 *     dishService.getDishes()
 *     dishService.getDishesByCategory(category)
 *     dishService.getDishById(id)
 * 这三个方法的「名字和签名」没变，只是「里面的实现」从内存换成了数据库。
 *
 * → 这是【分层】带来的收益：上层依赖的是「方法的签名」，不是「方法怎么实现」。
 *   换数据源时，调用方不需要动。
 *
 * ⚠️ 注意别把这个说成「面向接口编程」—— 这是个常见的过度表述。
 *   面向接口编程的意思是「Controller 依赖 DishService 接口，而不是 DishServiceImpl 类」，
 *   本项目并没有给 Service 抽接口（Controller 直接 @Autowired 具体类 DishService）。
 *   面试官如果追问「接口在哪」会很尴尬。
 *   如实说「我做了分层封装，方法签名稳定，所以换数据源不用改上层」就够了。

 *
 * ============================ 为什么删掉 initSeedData() ============================
 * 因为数据现在在数据库里了（init.sql 灌的 20 条）。
 * 内存里再存一份，就又回到「两个数据源」的老毛病上去了。
 * ★ 记住这句话：单一数据源不是「尽量做到」，而是「必须做到」。
 *   你那个串菜 bug 就是这么来的。
 */
@Service
public class DishService {

    @Autowired
    private DishMapper dishMapper;

    /**
     * 查全部上架菜品。
     *
     * 注意：直接返回 Mapper 查出来的 List，没有做 Collections.unmodifiableList 包装了。
     * 阶段 1 那个版本我加了不可变包装，是为了防止有代码往共享的 List 里塞东西。
     * 现在每次调用都从数据库查出一份新 List，本来就是独立的，不需要再包。
     */
    public List<Dish> getDishes() {
        return dishMapper.findActive();
    }

    /**
     * 按分类查。
     * 老代码是「查出全部，再在 Java 里 for 循环过滤」，
     * 现在是「把过滤条件交给数据库」，只把需要的数据查回来。
     *
     * 差别在哪？数据量大了就看得出来：
     *   老写法：10000 道菜全查回来，再在内存里筛出 50 道
     *   新写法：数据库直接返回 50 道
     * 这叫「把计算下推到数据层」。
     */
    public List<Dish> getDishesByCategory(String category) {
        if (category == null || category.isEmpty() || "all".equals(category)) {
            return getDishes();
        }
        return dishMapper.findActiveByCategory(category);
    }

    /** 按 id 查单个菜品。加购物车、看详情都靠它 */
    public Dish getDishById(Integer id) {
        if (id == null) {
            return null;
        }
        return dishMapper.findById(id);
    }

    /**
     * 页面上要用的分类列表。
     *
     * ⚠️ 这里有个「可以做得更好」的点，你自己判断要不要改：
     * 分类目前还是写死这 4 个。更合理的做法是从数据库查：
     *     SELECT DISTINCT category FROM dish WHERE active = true
     * 这样管理端加了一个新分类，页面筛选按钮自动就有了。
     *
     * 为什么现在不直接改？因为「写死 4 个分类」和「从库里查」各有取舍：
     *   - 写死：分类顺序可控、不会因为某分类暂时没菜就消失
     *   - 查库：新增分类自动出现
     * 这是产品决策不是技术缺陷。**面试官问「这里还能怎么优化」，你能答出这个
     * 取舍，比直接把代码改掉更值钱。**
     */
    public List<String> getCategories() {
        List<String> list = new ArrayList<>();
        list.add("主食");
        list.add("小吃");
        list.add("饮品");
        list.add("甜点");
        return list;
    }

    /**
     * 扣库存。给 OrderService 下单时调用。
     * @return true 扣成功；false 库存不足
     */
    public boolean decreaseStock(Integer dishId, Integer quantity) {
        return dishMapper.decreaseStock(dishId, quantity) > 0;
    }
}
