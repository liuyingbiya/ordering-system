package com.ordering.service;

import com.ordering.model.Cart;
import com.ordering.model.CartItem;
import com.ordering.model.Dish;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * 购物车业务层 —— 负责「从 session 里把购物车拿出来，没有就新建」。
 *
 * 【为什么把它单独抽出来？】
 * 原项目里，getOrCreateCart() 这个方法只写在 CartServlet 里（第 172 行），
 * 但 OrderServlet 也需要购物车（下单时要读），它却拿不到这个方法，
 * 只能自己再写一遍 session.getAttribute("cart") —— 又是一份重复代码。
 *
 * 抽成 Service 之后，谁要购物车都调 cartService.getOrCreateCart(session)，
 * 全项目一份逻辑。
 *
 * 【关于 session】
 * session 是「服务器给每个浏览器分配的独立储物柜」。
 * 你第一次访问，服务器给你一个 JSESSIONID 存到 cookie 里，
 * 之后每次请求带着它，服务器就知道「还是你」，就能取出你自己的购物车。
 * 换台电脑、换个浏览器，session 就不同了 —— 所以你的购物车不会跑到别人那儿去。
 */
@Service
public class CartService {

    /** 把购物车放进 session 时用的 key，统一在这儿定义，避免有人写 "cart" 有人写 "Cart" */
    private static final String SESSION_CART_KEY = "cart";
    /** 用户标识的 session key */
    private static final String SESSION_USER_KEY = "userId";

    @Autowired
    private DishService dishService;

    /**
     * 取购物车：session 里有就返回，没有就建一个新的（并放回 session）。
     */
    public Cart getOrCreateCart(HttpSession session) {
        Cart cart = (Cart) session.getAttribute(SESSION_CART_KEY);
        if (cart == null) {
            cart = new Cart(getOrCreateUserId(session));
            session.setAttribute(SESSION_CART_KEY, cart);
        }
        return cart;
    }

    /**
     * 用户标识：因为项目没做登录，就用 sessionId 拼一个游客 id。
     * 原来的写法是 "guest_" + session.getId()，逻辑一样。
     */
    public String getOrCreateUserId(HttpSession session) {
        String userId = (String) session.getAttribute(SESSION_USER_KEY);
        if (userId == null) {
            userId = "guest_" + session.getId();
            session.setAttribute(SESSION_USER_KEY, userId);
        }
        return userId;
    }

    /**
     * 加菜到购物车。
     *
     * ============================ 这里有一个必须校验的漏洞 ============================
     * 最初的写法是：
     *     cart.addItem(dish, quantity == null ? 1 : quantity);
     * 完全没有校验 quantity。这会导致：
     *
     *   ① 传负数（比如 quantity=-100）：
     *      下单时 SQL 是 UPDATE dish SET stock = stock - (-100) WHERE stock >= -100
     *      → 条件永远成立 → 库存【增加】100。恶意用户可以无限刷库存。
     *      同时小计 = 单价 × (-100) 是负数 → 订单总价变成负数。
     *
     *   ② 传超出库存的数（比如库存 5 却加 9999）：
     *      加购物车时不管，等到下单才在扣库存时失败 → 用户白填一遍收货信息。
     *
     *   ③ 传 0：加进购物车但数量为 0，页面显示奇怪。
     *
     * 所以服务端必须校验。**注意：前端 dish-detail.html 的 min="1" 只是浏览器约束，
     * 用 Postman 或改一下 HTML 就能绕过。所有前端校验都不可信，服务端必须再校验一遍。**
     * 这句话是 Web 安全的铁律，面试可以讲。
     *
     * @return true 加成功；false 表示菜品不存在、数量非法或超出库存
     */
    public boolean addToCart(HttpSession session, Integer dishId, Integer quantity) {
        if (dishId == null) {
            return false;
        }
        Dish dish = dishService.getDishById(dishId);
        if (dish == null) {
            return false;
        }

        int qty = (quantity == null) ? 1 : quantity;

        // ① 数量必须 >= 1
        if (qty < 1) {
            return false;
        }
        // ② 不能超过库存
        if (dish.getStock() == null || qty > dish.getStock()) {
            return false;
        }

        Cart cart = getOrCreateCart(session);
        // ③ 购物车里已有这道菜时，累加后的总数也不能超库存
        int alreadyInCart = 0;
        for (CartItem item : cart.getItems()) {
            if (item.getDish() != null && dishId.equals(item.getDish().getId())) {
                alreadyInCart = item.getQuantity();
                break;
            }
        }
        if (alreadyInCart + qty > dish.getStock()) {
            return false;
        }

        cart.addItem(dish, qty);
        session.setAttribute(SESSION_CART_KEY, cart);
        return true;
    }

    public void removeFromCart(HttpSession session, Integer dishId) {
        Cart cart = getOrCreateCart(session);
        cart.removeItem(dishId);
        session.setAttribute(SESSION_CART_KEY, cart);
    }

    /** 改数量。数量 <= 0 视为删除该项 */
    public void updateQuantity(HttpSession session, Integer dishId, Integer quantity) {
        Cart cart = getOrCreateCart(session);
        if (quantity == null || quantity <= 0) {
            cart.removeItem(dishId);
        } else {
            cart.updateQuantity(dishId, quantity);
        }
        session.setAttribute(SESSION_CART_KEY, cart);
    }

    public void clearCart(HttpSession session) {
        Cart cart = getOrCreateCart(session);
        cart.clear();
        session.setAttribute(SESSION_CART_KEY, cart);
    }
}
