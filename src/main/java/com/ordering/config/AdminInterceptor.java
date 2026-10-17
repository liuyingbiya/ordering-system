package com.ordering.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.net.URLEncoder;

/**
 * 管理端登录拦截器 —— 保护「查看全部订单」和「修改订单状态」这两个管理操作。
 *
 * ============================ 为什么需要它 ============================
 *
 * 加这个之前，任何人在浏览器打开 http://localhost:8080/order
 * 就能看到**所有顾客的姓名、电话、送餐地址**，还能改任意订单的状态。
 *
 * 这不是「功能没做完」，是**个人信息泄露**。
 * 一旦部署到公网，等于把用户通讯录挂到网上。
 *
 * ============================ 拦截器的原理（这个你要能讲） ============================
 *
 * Spring MVC 处理一个请求的完整链路是：
 *
 *     请求 → Filter（过滤器，Servlet 层）
 *            ↓
 *          DispatcherServlet
 *            ↓
 *          【HandlerInterceptor.preHandle】← 我们在这里拦截
 *            ↓
 *          Controller 方法
 *            ↓
 *          【HandlerInterceptor.postHandle】
 *            ↓
 *          渲染页面
 *            ↓
 *          【HandlerInterceptor.afterCompletion】
 *
 * Interceptor 和 Filter 的区别（面试常问）：
 *
 *   | | Filter | Interceptor |
 *   |---|---|---|
 *   | 属于 | Servlet 规范 | Spring MVC |
 *   | 能拿到什么 | 只有 request/response | 能拿到 **handler（即将执行哪个方法）** |
 *   | 能否用 Spring 的依赖注入 | 默认不能（由容器管理） | **能**（由 Spring 管理） |
 *   | 典型用途 | 编码、跨域、日志 | 登录校验、权限、日志 |
 *
 * 一句话：**Filter 更底层、拿不到「要执行哪个 Controller 方法」；
 * Interceptor 是 Spring 的，能拿到 handler，所以做权限校验更合适。**
 *
 * ============================ 为什么不用 Spring Security ============================
 *
 * 说实话，**真实项目应该用 Spring Security**：它有成熟的认证授权体系、
 * 密码加密（BCrypt）、CSRF 防护、记住我等等，不用自己造轮子。
 *
 * 我这里用手写拦截器，原因是：
 *   1. 这个项目只有一个「管理员」角色，不需要 Spring Security 那套复杂的模型
 *   2. 手写一遍能真正理解拦截器机制，而不是「配一下就完事」
 *
 * 面试被问到就如实说：「我用了拦截器做登录校验，因为只有一个角色、需求简单；
 * 如果要做多角色权限，应该换成 Spring Security。」**知道工具的边界，比会用工具更重要。**
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    /** session 里存「管理员已登录」的 key，统一在这里定义 */
    public static final String SESSION_ADMIN_KEY = "adminUser";

    /**
     * 在 Controller 方法执行【之前】调用。
     * 返回 true  → 放行，继续执行 Controller
     * 返回 false → 中断，Controller 不会被执行
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // ① 先看 session 里有没有登录标记
        HttpSession session = request.getSession(false);   // false = 没有就别新建
        if (session != null && session.getAttribute(SESSION_ADMIN_KEY) != null) {
            return true;   // 已登录，放行
        }

        // ② 没登录：记下用户原本想访问的地址，登录后跳回去
        //    ★ 这里也要做「只接受站内路径」的校验，否则会变成开放重定向漏洞
        //      （和 CartController.safeInternalPath 是同一个道理）
        String target = request.getRequestURI();
        if (request.getQueryString() != null) {
            target = target + "?" + request.getQueryString();
        }

        String loginUrl = request.getContextPath() + "/admin/login?redirect="
                + URLEncoder.encode(target, "UTF-8");
        response.sendRedirect(loginUrl);

        // ③ 返回 false，请求到此为止
        return false;
    }
}
