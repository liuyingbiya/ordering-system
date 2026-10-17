package com.ordering.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置类 —— 把拦截器注册上去，并指定它管哪些路径。
 *
 * ============================ 要拦哪些，不拦哪些 ============================
 *
 * 这个项目有两类页面：
 *
 *   顾客端（谁都能访问）：
 *     GET  /                     首页
 *     GET  /dish                 菜单
 *     GET  /dish/{id}            菜品详情
 *     GET  /cart                 购物车
 *     POST /cart/add 等          加购、改数量
 *     GET  /order/my             我的订单（按 session 里的 userId 过滤）
 *     GET  /order/{id}           订单详情
 *     GET  /order/checkout       结算页
 *     POST /order/create         下单
 *     POST /order/{id}/cancel    取消订单（已校验归属）
 *
 *   管理端（**只有管理员能访问**）：
 *     GET  /order                全部订单列表 ← 泄露所有顾客信息
 *     POST /order/{id}/status    修改订单状态 ← 能篡改业务数据
 *
 * 所以只拦后两个。
 *
 * ⚠️ 这里有个容易出错的地方：
 *    `/order`      和 `/order/my`、`/order/{id}` 是不同的路径。
 *    如果粗心写成 `addPathPatterns("/order/**")`，
 *    会把「我的订单」「订单详情」也拦掉 —— 顾客就看不了自己的订单了。
 *    **拦截范围一定要精确到具体路径，不能图省事用通配符。**
 *
 * ============================ 一个必须知道的技术细节 ============================
 *
 * 拦截器是在「路由匹配之后、Controller 执行之前」运行的。
 * 也就是说：**它只对被 Spring MVC 路由到的请求生效。**
 *
 * 如果有人访问一个根本不存在的地址（比如 /abc），
 * 根本不会走到拦截器 —— 直接 404。
 * 所以拦截器不能当作「全局安全防护」来用，
 * 它管的是「已匹配到 Controller 的那部分请求」。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AdminInterceptor adminInterceptor;

    @Autowired
    private CsrfInterceptor csrfInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        /*
         * ================== 拦截器的执行顺序很重要 ==================
         *
         * 注册顺序就是执行顺序。先 CSRF、后登录校验，理由：
         *
         *   CSRF 校验的是「请求来源可不可信」，这是一个**更基础**的判断 ——
         *   一个跨站伪造的请求，哪怕它带着合法的登录 cookie，也不该被处理。
         *
         *   如果反过来（先查登录），攻击者伪造的请求会先被判定为「已登录」
         *   而进入业务逻辑，风险更大。
         *
         *   ★ 原则：**越基础、越严格的安全检查越靠前。**
         */
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/**")     // 所有请求都要过来源校验（内部只查写操作）
                // ⚠️ 必须排除 /error，否则会「自我递归」：
                //   异常触发后容器会 forward 到 /error，那又是一次请求分发，
                //   拦截器再拦一次、再抛异常、再 forward…… 最后可能栈溢出。
                //   这是个很容易忽略的坑。
                .excludePathPatterns("/error");

        registry.addInterceptor(adminInterceptor)
                // 管理端订单列表。写两个是为了兼容带斜杠的 /order/
                .addPathPatterns("/order", "/order/")
                // 管理端改订单状态：/order/5/status
                .addPathPatterns("/order/*/status")
                // 排除登录页本身，否则登录页也会被拦，形成死循环
                .excludePathPatterns("/admin/**");
    }
}
