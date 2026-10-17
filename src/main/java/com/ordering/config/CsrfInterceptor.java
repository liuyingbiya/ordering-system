package com.ordering.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;

/**
 * CSRF 防护 —— 校验「写操作」的请求来源是不是本站。
 *
 * ============================ CSRF 到底是什么 ============================
 *
 * 假设你（管理员）已经登录了后台，浏览器里存着 session cookie。
 * 这时你点开一个恶意网页，页面里有这么一段：
 *
 *     <form action="http://你的站点/order/5/status" method="post">
 *         <input name="status" value="4">
 *     </form>
 *     <script>document.forms[0].submit()</script>
 *
 * 浏览器提交这个表单时，**会自动带上你在「你的站点」的 cookie**。
 * 服务器看到 cookie 有效，就认为「是管理员本人操作的」，于是订单状态被改了。
 *
 * 关键点：**攻击者拿不到你的 cookie，但他不需要拿到 —— 他只要让浏览器
 * 「带着 cookie 去发请求」就够了。** 这就是 CSRF（跨站请求伪造）。
 *
 * ⚠️ 这就是为什么：**加了登录之后，CSRF 才成为真问题。**
 *    没有登录态的时候，攻击者伪造请求也没什么身份可以冒用。
 *
 * ============================ 为什么当初没做、现在必须做 ============================
 * 在阶段 1～3 里，项目没有登录态（身份就是 sessionId），
 * CSRF 的危害有限。加了管理端登录之后，这里就成了真漏洞：
 * 攻击者可以诱导已登录的管理员去改订单状态。
 *
 * ============================ 三种主流防护，我选了第二种 ============================
 *
 * 【1】CSRF Token（业界标准做法）
 *      服务端生成随机 token 存 session，每个表单带一个隐藏字段，
 *      提交时比对。攻击者伪造的页面拿不到这个 token，所以伪造失败。
 *      优点：最严格。
 *      缺点：**每个表单都要改**，本项目有 14 个 POST 表单，
 *            全都要加 `<input type="hidden" name="_csrf" ...>`，
 *            而且漏一个就有一个洞。
 *      （Spring Security 的默认方案就是它，用框架时基本是自动完成的。）
 *
 * 【2】校验 Origin / Referer 头  ← **本项目采用**
 *      浏览器发起**跨站**请求时，会带上 `Origin` 头，
 *      值就是发起页面的域名。服务端只要确认「这个 Origin 是不是我自己」，
 *      就能识破跨站伪造。
 *      优点：**零表单改动**，一个拦截器搞定，拦截点集中、不容易漏。
 *      缺点：比 Token 略弱 —— 极老的浏览器可能不发 Origin；
 *            同源的 XSS 挡不住（但 XSS 本来就该用别的手段防）。
 *
 * 【3】SameSite Cookie
 *      给 cookie 加 `SameSite=Lax/Strict`，浏览器就不会在跨站请求里带上它。
 *      简单有效，但要 Spring Boot 2.6+ 才能方便地通过配置设置，
 *      而且它是「浏览器配合」，不能算服务端自己的校验。
 *
 * 【为什么选 2】
 *   这是本项目里「投入产出比」最高的选择：一个拦截器集中处理，
 *   不会有「漏改某个表单」的风险。**面试被问到时如实说：
 *   我知道 Token 是更标准的做法，我选 Origin 校验是因为改动面小、
 *   不容易漏；真实项目应该用框架内置的 Token 方案。**
 *
 * ============================ 一个必须讲清楚的设计决策 ============================
 *
 * 「如果请求既没有 Origin 也没有 Referer，怎么办？」
 *
 * 我选择：**放行**。
 *
 * 理由：浏览器发跨站请求时**一定**会带 Origin（现代浏览器对 POST 一律带）。
 * 一个既没有 Origin 也没有 Referer 的请求，基本可以断定不是浏览器发起的
 * —— 是 curl、Postman、或者服务端之间的调用。这类客户端本来就不会
 * 自动携带用户 cookie，**不构成 CSRF 攻击面**。
 *
 * 如果不放行会怎样？你自己用 Postman 调接口、或者写测试脚本时全部会被挡，
 * 而那并不是真实攻击场景。
 *
 * ★ 这个「放行」不是偷懒，是一个有明确前提的取舍。前提是：
 *   「浏览器一定带 Origin」。这个前提在 2020 年之后的浏览器上成立。
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CsrfInterceptor.class);

    /**
     * 在 Controller 方法执行前校验来源。
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // ① 只校验「会改变数据」的方法。
        //    GET/HEAD/OPTIONS 是只读的，挡它们没有意义还会误伤正常浏览。
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");

        // ② 优先看 Origin（跨站请求一定会带）
        if (origin != null) {
            // Origin: null 的情况：沙箱 iframe、file:// 页面、某些重定向场景。
            // 这些都不可信，直接拒绝。
            if ("null".equals(origin)) {
                log.warn("CSRF 拦截：Origin 为 null，uri={}", request.getRequestURI());
                throw new CsrfException("请求来源不可信");
            }
            if (isSameOrigin(origin, request)) {
                return true;
            }
            log.warn("CSRF 拦截：跨站请求 origin={}, uri={}", origin, request.getRequestURI());
            throw new CsrfException("检测到跨站请求，已拦截");
        }

        // ③ Origin 没有，退一步看 Referer
        if (referer != null && !referer.isEmpty()) {
            if (isSameOrigin(referer, request)) {
                return true;
            }
            log.warn("CSRF 拦截：Referer 不匹配 referer={}, uri={}", referer, request.getRequestURI());
            throw new CsrfException("检测到跨站请求，已拦截");
        }

        // ④ 两个头都没有：判定为非浏览器客户端（curl / Postman / 服务间调用），
        //    这类客户端不会自动带用户 cookie，不构成 CSRF 攻击面，放行。
        //    ★ 这是有前提的取舍，见类注释最后一段。
        return true;
    }

    /**
     * 判断某个来源（Origin 或 Referer 的值）是不是「本站」。
     *
     * Origin 的格式：`http://localhost:8080`（只有协议+域名+端口，没有路径）
     * Referer 的格式：`http://localhost:8080/order/5`（带路径）
     *
     * 所以 Referer 要先解析出协议+域名+端口再比。
     *
     * ⚠️ 为什么要比对「协议+域名+端口」三者，而不是只比域名？
     *   因为 `http://localhost:8080` 和 `http://localhost:9999` 是不同源。
     *   只比域名会被「同域名不同端口」绕过。
     *   这个「协议+域名+端口」叫 **同源（same-origin）**，是浏览器的基本安全模型。
     */
    private boolean isSameOrigin(String source, HttpServletRequest request) {
        try {
            URI uri = new URI(source);

            String sourceHost = uri.getHost();
            // 没有 host 的（比如相对路径）不可能是我们发出的 Origin，直接不匹配
            if (sourceHost == null) {
                return false;
            }

            String requestHost = request.getServerName();
            if (!sourceHost.equalsIgnoreCase(requestHost)) {
                return false;
            }

            // 端口：URI 里没写端口时是 -1，需要按协议取默认端口
            int sourcePort = uri.getPort();
            if (sourcePort == -1) {
                sourcePort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            if (sourcePort != request.getServerPort()) {
                return false;
            }

            // 协议：http / https 也要一致（避免 https 页面伪造到 http 站点）
            String sourceScheme = uri.getScheme();
            return sourceScheme != null && sourceScheme.equalsIgnoreCase(request.getScheme());

        } catch (Exception e) {
            // 解析失败的一律当作不可信（失败即关闭，fail-closed）
            log.warn("CSRF 拦截：来源解析失败 source={}", source, e);
            return false;
        }
    }
}
