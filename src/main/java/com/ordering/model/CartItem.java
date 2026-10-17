package com.ordering.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车里的一项 = 一道菜 + 数量。
 * 原样从原项目搬过来。
 *
 * 为什么要有这个类，而不是直接用 Map<Dish, Integer>？
 * 因为它把「小计怎么算」这件事收在了自己身上（见 getSubtotal），
 * 而不是散落在页面、Controller 各处去乘。这是面向对象里很基本的封装思想。
 */
public class CartItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private Dish dish;
    private Integer quantity;

    public CartItem() {
    }

    public CartItem(Dish dish, Integer quantity) {
        this.dish = dish;
        this.quantity = quantity;
    }

    public Dish getDish() {
        return dish;
    }

    public void setDish(Dish dish) {
        this.dish = dish;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    /** 小计 = 单价 × 数量 */
    public BigDecimal getSubtotal() {
        if (dish != null && dish.getPrice() != null) {
            return dish.getPrice().multiply(new BigDecimal(quantity));
        }
        return BigDecimal.ZERO;
    }
}
