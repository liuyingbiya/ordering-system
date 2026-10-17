package com.ordering.controller;

import com.ordering.model.Dish;
import com.ordering.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 首页 Controller。
 *
 * ============================ 先看它的「前身」 ============================
 * 前身 1：原来的 IndexController（Servlet）
 *   @WebServlet(urlPatterns = {"/index", ""})
 *   protected void doGet(HttpServletRequest request, HttpServletResponse response) {
 *       ... request.setAttribute("dishes", dishes);
 *       request.getRequestDispatcher("index.jsp").forward(request, response);
 *   }
 *
 * 前身 2：原来的 index.jsp 顶部那一大块 Java 代码
 *   <% java.util.List<com.ordering.model.Dish> dishes = new java.util.ArrayList<>();
 *      dishes.add(new com.ordering.model.Dish(1, "招牌红烧肉", ...));  // 硬编码 20 道菜
 *      application.setAttribute("dishes", dishes); %>
 *
 * ============================ 现在变成了什么 ============================
 * 整个类只有 10 行左右的有效代码，而且：
 *   - 没有 new ArrayList，没有 setAttribute，没有 forward
 *   - 数据从 DishService 拿（唯一数据源）
 *   - 「渲染哪个页面」由返回值字符串 "index" 表示，Spring 自动找 templates/index.html
 *
 * ============================ 三个动作的对照表（重点背这个） ============================
 *
 *   | 要干的事       | 老写法（Servlet）                        | 新写法（Spring MVC）        |
 *   |----------------|------------------------------------------|------------------------------|
 *   | 接收请求       | 继承 HttpServlet，重写 doGet()            | @GetMapping("/")             |
 *   | 往页面传数据   | request.setAttribute("dishes", dishes)   | Model 参数 + model.addAttribute(...) |
 *   | 决定渲染页面   | request.getRequestDispatcher("x.jsp").forward(...) | return "x"          |
 *
 * 三件事一一对应，只是换了表达方式。理解了这张表，你就理解了 Spring MVC 的一半。
 */
@Controller
public class HomeController {

    /**
     * @Autowired = 「让 Spring 把这个对象给我」。
     *
     * 我们从来没有写过 new DishService()，那这个对象哪来的？
     * 答案：DishService 类上面有 @Service 注解，Spring 启动时扫描到它，
     * 自己 new 了一个实例放进「容器」里管着。
     * 这里 @Autowired 就是告诉 Spring：「容器里那个 DishService，注入到我这个字段上」。
     *
     * 这个机制叫「依赖注入（DI）」/「控制反转（IoC）」，是 Spring 的核心。
     * 好处：HomeController 不需要知道 DishService 是怎么创建出来的，
     * 也不需要自己管它的生命周期，拿到手就能用。
     */
    @Autowired
    private DishService dishService;

    /**
     * 访问 http://localhost:8080/ 时执行。
     *
     * 对比原来的 IndexController：
     *   @WebServlet(urlPatterns = {"/index", ""})
     * 原来要写空字符串 "" 来匹配根路径，现在直接写 "/" 更直观。
     * 这里同时接 "/" 和 "/index"，是为了让老地址 /index 也还能访问。
     *
     * @param model  Spring 自动传进来的「数据袋子」，你往里放东西，页面就能取到
     * @return 视图名。返回 "index" → 渲染 src/main/resources/templates/index.html
     *         （模板位置由 application.properties 里的 spring.thymeleaf.prefix 决定）
     */
    @GetMapping({"/", "/index"})
    public String home(Model model) {
        List<Dish> dishes = dishService.getDishes();

        // 相当于原来的 request.setAttribute("dishes", dishes)
        model.addAttribute("dishes", dishes);

        // 相当于原来的 request.getRequestDispatcher("index.jsp").forward(...)
        return "index";
    }
}
