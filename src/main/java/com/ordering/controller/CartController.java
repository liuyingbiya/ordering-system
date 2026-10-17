package com.ordering.controller;

import com.ordering.model.Cart;
import com.ordering.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;

/**
 * 购物车 Controller —— 对应原来的 CartServlet。
 *
 * ============================ 本阶段最重要的知识点：GET 和 POST 的分工 ============================
 *
 * 先看原项目的问题。
 * 原来的 CartServlet 里，「加入购物车」是通过一个 GET 请求完成的：
 *   dishes.jsp 第 331 行：<a href=".../cart?action=add&dishId=${dish.id}">加入购物车</a>
 *   index.jsp  第 124 行：<a href=".../cart?action=add&dishId=${dish.id}" class="add-btn">加入购物车</a>
 * 都是 <a> 标签，也就是 GET。
 *
 * 这违反 HTTP 语义，会带来三个真实问题：
 *   1. 浏览器/代理的预抓取会「提前加购物车」—— 用户还没点，浏览器为了加速已经请求过了。
 *   2. 用户按 F5 刷新，浏览器弹「是否重新提交」；再点确定就重复加了一次。
 *   3. 别人发给你一个链接 /cart?action=add&dishId=1，你一点就莫名其妙多了一样东西。
 *
 * 【记住这条规则】
 *   GET  —— 只做「读」，不改变服务器数据。（查列表、查详情、看购物车）
 *   POST —— 做「写」，会改变数据。（加购物车、改数量、删除、下单）
 * 这叫「幂等性」相关的问题：GET 请求重复执行多少次，结果都应该一样。
 *
 * 所以下面：「看购物车」是 @GetMapping，「加/改/删/清空」全是 @PostMapping。
 * 页面上的「加入购物车」也从 <a> 标签改成了 <form method="post">。
 * 这也是你面试可以讲的一个点：「我迁移时顺手修正了原来的一个 HTTP 语义问题」。
 *
 * ============================ 第二个知识点：重定向后消息怎么传 ============================
 * 老代码是这样提示的：session.setAttribute("message", "已添加到购物车");
 * 直接用 session 存消息，有个毛病 —— 这条消息会一直留在 session 里，
 * 比如你加了一次菜，之后访问别的页面它还在。
 *
 * 新写法用 RedirectAttributes.addFlashAttribute()，它专为「重定向后显示一次性提示」设计：
 * 只在下一次请求里能取到，取完自动消失。页面上还是用 ${message} 取，用法没变。
 */
@Controller
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private CartService cartService;

    /** 看购物车：只读，用 GET */
    @GetMapping
    public String view(HttpSession session, Model model) {
        Cart cart = cartService.getOrCreateCart(session);
        model.addAttribute("cart", cart);
        return "cart";
    }

    /**
     * 加入购物车：写操作，用 POST。
     *
     * @param dishId  哪个菜。@RequestParam 默认必传，不传会报 400 错误。
     *                【纠正一个常见误解】：老代码传空 dishId 并不会崩 ——
     *                CartServlet 第 94 行有 if (dishIdStr != null && !dishIdStr.isEmpty()) 守卫，
     *                空值进来会静默跳过、什么都不做。
     *                新写法真正的改进是：参数缺失直接返回 400 并告诉你缺什么，
     *                而不是「静默什么都不发生、用户以为成功了」。
     * @param quantity 数量，defaultValue = "1" 表示不传就当 1。
     *                 老代码是手写 if (quantityStr != null && !quantityStr.isEmpty()) 判断，现在一行注解解决。
     *                 ⚠️ 但数量本身合不合法（是否 < 1、是否超库存）由 CartService 校验，
     *                    defaultValue 只解决「没传」的情况。
     * @param referer 从哪个页面来的。Spring 可以直接把请求头注入成方法参数，
     *                比老代码 request.getHeader("Referer") 更省事。
     *                为什么需要它？因为用户在「菜单页」和「首页」都可能点加购，
     *                加完之后应该回到他来时的那个页面，而不是固定跳一个地方。
     */
    @PostMapping("/add")
    public String add(@RequestParam("dishId") Integer dishId,
                      @RequestParam(value = "quantity", defaultValue = "1") Integer quantity,
                      @org.springframework.web.bind.annotation.RequestHeader(value = "Referer", required = false) String referer,
                      HttpSession session,
                      RedirectAttributes redirectAttributes) {

        boolean ok = cartService.addToCart(session, dishId, quantity);
        if (ok) {
            // flash 属性：只在下一次请求里有效，用于重定向后显示提示
            redirectAttributes.addFlashAttribute("message", "已添加到购物车");
        } else {
            // 可能是菜品不存在、数量 < 1、或超过库存
            redirectAttributes.addFlashAttribute("error", "添加失败：菜品不存在、数量不合法或超出库存");
        }

        // 回到来时的页面；如果来源不可信或拿不到，就回菜单页
        String safeReferer = safeInternalPath(referer);
        if (safeReferer != null) {
            return "redirect:" + safeReferer;
        }
        return "redirect:/dish";
    }

    /**
     * ============================ 修复「开放重定向」漏洞 ============================
     *
     * 最初的写法是：
     *     if (referer != null && !referer.isEmpty()) {
     *         return "redirect:" + referer;
     *     }
     *
     * Referer 是【浏览器提供的请求头，客户端可以随便改】。
     * 攻击者构造一个页面，链接指向你的 /cart/add，并伪造 Referer: https://evil.com/钓鱼页
     * 用户点一下 → 你的服务器返回 302 Location: https://evil.com/钓鱼页
     * → 用户看到地址栏从「你信任的站点」跳到了钓鱼站。
     * 这叫「开放重定向」（Open Redirect），常被用来做钓鱼链的一环。
     *
     * 这个漏洞比它看起来严重：用户信任的是你的域名，最终却落到攻击者的页面。
     *
     * 【修法】只接受「站内相对路径」，其余一律忽略：
     *   - 必须以单个 / 开头
     *   - 不能以 // 或 /\ 开头（那是「协议相对 URL」，//evil.com 会跳到外站）
     *   - 不能包含协议（http://、https://、javascript: 等）
     *
     * 这是「输入校验白名单」思路：不是去列举坏东西（黑名单永远列不全），
     * 而是只放行明确安全的东西。
     *
     * @return 安全的站内路径，或 null（表示不可信，调用方走默认跳转）
     */
    private String safeInternalPath(String referer) {
        if (referer == null || referer.isEmpty()) {
            return null;
        }
        // 必须是以 / 开头的站内路径，且不能是 // 或 /\（协议相对 URL，会跳到外站）
        if (!referer.startsWith("/") || referer.startsWith("//") || referer.startsWith("/\\")) {
            return null;
        }
        // 带协议或冒号的直接拒绝（http:、https:、javascript:、data: 等）
        String lower = referer.toLowerCase();
        if (lower.startsWith("http:") || lower.startsWith("https:")
                || lower.contains("://") || lower.startsWith("javascript:")) {
            return null;
        }
        // 去掉查询串里的换行等控制字符，避免响应头注入
        if (referer.indexOf('\r') >= 0 || referer.indexOf('\n') >= 0) {
            return null;
        }
        return referer;
    }

    /** 修改数量：写操作，用 POST。数量减到 0 就相当于删除 */
    @PostMapping("/update")
    public String update(@RequestParam("dishId") Integer dishId,
                         @RequestParam("quantity") Integer quantity,
                         HttpSession session) {
        cartService.updateQuantity(session, dishId, quantity);
        return "redirect:/cart";
    }

    /** 从购物车移除某一项：写操作，用 POST */
    @PostMapping("/remove")
    public String remove(@RequestParam("dishId") Integer dishId,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        cartService.removeFromCart(session, dishId);
        redirectAttributes.addFlashAttribute("message", "已从购物车移除");
        return "redirect:/cart";
    }

    /** 清空购物车：写操作，用 POST */
    @PostMapping("/clear")
    public String clear(HttpSession session, RedirectAttributes redirectAttributes) {
        cartService.clearCart(session);
        redirectAttributes.addFlashAttribute("message", "购物车已清空");
        return "redirect:/cart";
    }
}
