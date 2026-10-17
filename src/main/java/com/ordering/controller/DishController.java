package com.ordering.controller;

import com.ordering.model.Dish;
import com.ordering.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 菜品 Controller —— 对应原来的 DishServlet。
 *
 * ============================ 老代码的问题：一个 Servlet 干三件事 ============================
 * 原来的 DishServlet 是这样分派的（第 30-51 行）：
 *
 *   String action = request.getParameter("action");
 *   switch (action) {
 *       case "list":     listDishes(request, response);      break;
 *       case "category": listByCategory(request, response);  break;
 *       case "detail":   getDishDetail(request, response);   break;
 *       default:         listDishes(request, response);
 *   }
 *
 * 打开的地址长这样：/dish?action=category&category=主食
 * 这就是所谓的「一个 URL 干所有事」，靠 action 参数区分。
 *
 * ============================ 现在改成了「RESTful 风格」 ============================
 * 每个 URL 只表示一个「资源」，用 URL 本身表达意图：
 *
 *   | 老地址                                   | 新地址                        |
 *   |------------------------------------------|-------------------------------|
 *   | /dish?action=list                        | /dish                         |
 *   | /dish?action=category&category=主食       | /dish?category=主食           |
 *   | /dish?action=detail&id=3                 | /dish/3                       |
 *
 * 注意最后一行：id 从「查询参数」变成了「URL 路径的一部分」。
 * 这就叫 RESTful 风格 —— 面试官问「你了解 RESTful 吗」，
 * 你可以直接拿这个项目举例：菜品是资源，/dish/3 表示「第 3 号菜品」。
 *
 * 好处：URL 更短、更直观、更容易被搜索引擎和缓存识别，
 * 而且一个方法只管一件事，不用写 switch。
 */
@Controller
@RequestMapping("/dish")
public class DishController {

    @Autowired
    private DishService dishService;

    /**
     * 菜品列表 + 分类筛选。
     *
     * 老代码里 list 和 category 是两个 case，现在合成一个方法，
     * 因为「筛不筛选」只是多一个参数的区别。
     *
     * @RequestParam(required = false) 的含义：
     *   这个参数可以不传。访问 /dish 时不传 category，category 就是 null，
     *   DishService 会返回全部菜品。
     *   等价于老代码里的：String category = request.getParameter("category");
     */
    @GetMapping
    public String list(@RequestParam(value = "category", required = false) String category,
                       Model model) {
        model.addAttribute("dishes", dishService.getDishesByCategory(category));
        model.addAttribute("currentCategory", category);
        model.addAttribute("categories", dishService.getCategories());
        return "dishes";
    }

    /**
     * 菜品详情。
     *
     * 老地址：/dish?action=detail&id=3
     * 新地址：/dish/3
     *
     * @PathVariable 就是「把 URL 路径里 {id} 那一段取出来，塞进参数 id」。
     *   访问 /dish/3 时，id = 3。
     *   对比 @RequestParam 是从 ? 后面取值，@PathVariable 是从路径里取值。
     *   这两个的区别是面试高频考点，你记这句话：
     *     「查一组用 @RequestParam，查一个具体的用 @PathVariable」。
     */
    @GetMapping("/{id}")
    public String detail(@org.springframework.web.bind.annotation.PathVariable("id") Integer id,
                         Model model) {
        Dish dish = dishService.getDishById(id);
        if (dish == null) {
            // 菜品不存在：重定向回列表页，避免页面报错
            return "redirect:/dish";
        }
        model.addAttribute("dish", dish);
        return "dish-detail";
    }
}
