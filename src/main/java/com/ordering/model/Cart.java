package com.ordering.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物车。
 *
 * 原样从原项目搬过来 —— 但这里有个「重点」，阶段 4 会回来处理：
 * 购物车目前「不落库」，它活在用户的 session（服务器内存）里。
 * 后果：重启服务购物车就空了；换一台服务器部署，用户就看不到自己的购物车。
 *
 * 为什么暂时不改成数据库表？因为购物车变更极其频繁（用户每点一次加减号就是一次写），
 * 真实项目里通常也是先用缓存（Redis）扛，定时或下单时才落库。
 * 等你把前 3 个阶段吃透了，再把它做成第 4 张表，那时你就能讲清楚「为什么」了。
 */
public class Cart implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<CartItem> items;
    private String userId;

    public Cart() {
        this.items = new ArrayList<>();
    }

    public Cart(String userId) {
        this.userId = userId;
        this.items = new ArrayList<>();
    }

    public List<CartItem> getItems() {
        return items;
    }

    public void setItems(List<CartItem> items) {
        this.items = items;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** 加菜：如果购物车里已经有这道菜，就累加数量，而不是新增一行 */
    public void addItem(Dish dish, Integer quantity) {
        for (CartItem item : items) {
            if (item.getDish().getId().equals(dish.getId())) {
                item.setQuantity(item.getQuantity() + quantity);
                return;
            }
        }
        items.add(new CartItem(dish, quantity));
    }

    public void removeItem(Integer dishId) {
        items.removeIf(item -> item.getDish().getId().equals(dishId));
    }

    public void updateQuantity(Integer dishId, Integer quantity) {
        for (CartItem item : items) {
            if (item.getDish().getId().equals(dishId)) {
                item.setQuantity(quantity);
                return;
            }
        }
    }

    public void clear() {
        items.clear();
    }

    /** 总价：遍历每一项累加小计。注意 BigDecimal 用 add() 而不是 + */
    public BigDecimal getTotalPrice() {
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : items) {
            total = total.add(item.getSubtotal());
        }
        return total;
    }

    /** 总件数：页面上购物车角标显示的数字 */
    public Integer getTotalQuantity() {
        Integer total = 0;
        for (CartItem item : items) {
            total += item.getQuantity();
        }
        return total;
    }
}
