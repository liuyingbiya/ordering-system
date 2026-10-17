package com.ordering.controller;

import com.ordering.model.Cart;
import com.ordering.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import javax.servlet.http.HttpSession;

/**
 * 全局模型增强 —— 让「每个页面都能拿到购物车件数」这件事只写一遍。
 *
 * 【它解决什么问题？】
 * 看原来的 JSP：每个页面的导航栏都要显示购物车角标，
 * 于是 dishes.jsp 第 351 行、index.jsp 第 42-48 行，各自写了一遍
 * 「从 session 里取 cart，算 totalQuantity」。
 * 8 个页面就有 8 份重复逻辑，改一处要改 8 个地方。
 *
 * 现在：@ControllerAdvice + @ModelAttribute 组合的含义是
 * 「本项目每一个 Controller 方法执行前，都先执行一次这个方法，
 *   并把返回值放进那个页面的 Model 里」。
 *
 * 所以任何模板里都能直接写 ${cartCount}，不用每个 Controller 各自 addAttribute。
 *
 * 【面试点】@ControllerAdvice 是 Spring MVC 的全局增强注解，三种典型用途：
 *   1. 全局数据（就像这里）
 *   2. 全局异常处理（配合 @ExceptionHandler）
 *   3. 全局数据绑定（@InitBinder）
 * 你能说出「我用它给所有页面统一注入购物车数量，避免在每个 Controller 里重复写」，
 * 就已经比只会背注解的人强了。
 */
@ControllerAdvice
public class GlobalModelAdvice {

    /** 购物车件数少于这个值就不显示了 */
    private static final int HIDDEN_BADGE_THRESHOLD = 0;

    @Autowired
    private CartService cartService;

    /**
     * 返回的 "cartCount" 会自动加进每个页面的 Model。
     * 注意：这里用 session.getAttribute 直接取，不调用 getOrCreateCart，
     * 因为「只是显示一个角标」不该顺手给用户创建一个购物车对象。
     */
    @ModelAttribute("cartCount")
    public int cartCount(HttpSession session) {
        Cart cart = (Cart) session.getAttribute("cart");
        if (cart == null) {
            return HIDDEN_BADGE_THRESHOLD;
        }
        return cart.getTotalQuantity();
    }
}
