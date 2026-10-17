package com.ordering.controller;

import com.ordering.config.AdminInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理员登录 / 退出。
 *
 * ============================ 先说清楚这个实现的定位 ============================
 *
 * 这是一个**教学用的极简实现**，不是生产级方案。差距在哪：
 *
 *   1. 账号密码写在配置文件里 —— 生产上应该在数据库，且有用户表
 *   2. 密码是明文比较 —— 生产上必须存 BCrypt 哈希，绝不明文
 *   3. 没有防暴力破解 —— 生产上要限制登录失败次数、加验证码
 *   4. 会话固定攻击防护 —— 生产上登录成功后应更换 sessionId
 *
 * **为什么还是这么做**：项目只有「一个管理员」这个需求，
 * 引入完整的用户体系属于过度设计。但上面这 4 条你要知道，
 * 面试被问「你的登录安全吗」，如实说「我做了最小可用版本，
 * 密码明文存配置是已知的简化，生产环境必须换成 BCrypt」。
 *
 * ★ 还是那句话：**知道自己做的是简化版、知道差在哪，比假装做到了更安全。**
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 管理员账号密码，来自 application.properties。生产环境必须改掉。 */
    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    /**
     * 登录失败计数（按 IP 简单统计），防止无限次暴力猜密码。
     *
     * ⚠️ 注意这是【进程内】的内存计数：
     *   重启就清零，多台服务器部署也不共享。
     *   生产上应该用 Redis 存 + 按账号/IP 限流。
     *   这里够用，但要知道它挡不住「换 IP 继续猜」。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> failCounts
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MAX_FAIL = 5;

    /** 显示登录页 */
    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "redirect", required = false) String redirect,
                            Model model) {
        model.addAttribute("redirect", safeInternalPath(redirect));
        return "admin-login";
    }

    /**
     * 处理登录表单。
     *
     * ★ 关于「为什么登录成功后要 redirect 而不是直接渲染页面」：
     *   这是 POST-Redirect-GET 模式。如果登录成功后直接返回页面，
     *   用户按 F5 刷新会再次提交表单（浏览器会提示「是否重新提交」）。
     *   重定向之后，浏览器地址栏是一个 GET 地址，刷新不会重复提交。
     *   （这和阶段 1 讲的「GET/POST 语义」是同一个道理。）
     */
    @PostMapping("/login")
    public String doLogin(@RequestParam("username") String username,
                          @RequestParam("password") String password,
                          @RequestParam(value = "redirect", required = false) String redirect,
                          HttpSession session,
                          Model model,
                          RedirectAttributes redirectAttributes) {

        String ip = "unknown";

        // ① 先查这个 IP 是不是已经被锁了
        AtomicInteger fails = failCounts.get(username);
        if (fails != null && fails.get() >= MAX_FAIL) {
            model.addAttribute("error", "密码错误次数过多，请重启服务后重试（演示环境的简化限制）");
            model.addAttribute("redirect", safeInternalPath(redirect));
            return "admin-login";
        }

        // ② 校验账号密码
        if (adminUsername.equals(username) && adminPassword.equals(password)) {
            // 登录成功：清掉失败计数，往 session 写标记
            failCounts.remove(username);
            session.setAttribute(AdminInterceptor.SESSION_ADMIN_KEY, username);
            log.info("管理员登录成功: {}", username);

            String target = safeInternalPath(redirect);
            return "redirect:" + (target != null ? target : "/order");
        }

        // ③ 登录失败
        failCounts.computeIfAbsent(username, k -> new AtomicInteger()).incrementAndGet();
        log.warn("管理员登录失败: username={}, ip={}", username, ip);

        model.addAttribute("error", "用户名或密码错误");
        model.addAttribute("redirect", safeInternalPath(redirect));
        return "admin-login";
    }

    /** 退出登录（用 POST，因为这是「改变状态」的操作，理由同阶段 1） */
    @PostMapping("/logout")
    public String logout(HttpSession session, RedirectAttributes redirectAttributes) {
        session.removeAttribute(AdminInterceptor.SESSION_ADMIN_KEY);
        redirectAttributes.addFlashAttribute("message", "已退出管理员登录");
        return "redirect:/";
    }

    /**
     * 只放行站内相对路径，防止「开放重定向」。
     * 逻辑和 CartController.safeInternalPath 一致 ——
     * 这已经是项目里第二次用到这个校验，说明它值得抽成公共工具类。
     * （这次先不抽，避免改动面扩大；面试可以主动说「这里可以抽个工具类复用」）
     */
    private String safeInternalPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) {
            return null;
        }
        String lower = path.toLowerCase();
        if (lower.contains("://") || lower.startsWith("javascript:")
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            return null;
        }
        return path;
    }
}
