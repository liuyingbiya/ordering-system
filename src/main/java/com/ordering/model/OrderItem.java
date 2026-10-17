package com.ordering.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细（订单里的一道菜）—— 对应数据库 order_item 表。
 * 原样从原项目搬过来。
 */
public class OrderItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private Integer orderId;
    private Integer dishId;
    /** 下单时的菜名。注意这里冗余存了一份，而不是只存 dishId 去关联查 —— 
     *  因为菜名/价格以后可能会改，订单要保留「当时下单」的信息，这是订单类系统的通行做法。 */
    private String dishName;
    /** 下单时的单价 */
    private BigDecimal price;
    private Integer quantity;
    /** 小计 = 单价 × 数量 */
    private BigDecimal subtotal;

    public OrderItem() {
    }

    public OrderItem(Integer dishId, String dishName, BigDecimal price, Integer quantity) {
        this.dishId = dishId;
        this.dishName = dishName;
        this.price = price;
        this.quantity = quantity;
        this.subtotal = price.multiply(new BigDecimal(quantity));
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getOrderId() {
        return orderId;
    }

    public void setOrderId(Integer orderId) {
        this.orderId = orderId;
    }

    public Integer getDishId() {
        return dishId;
    }

    public void setDishId(Integer dishId) {
        this.dishId = dishId;
    }

    public String getDishName() {
        return dishName;
    }

    public void setDishName(String dishName) {
        this.dishName = dishName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}
