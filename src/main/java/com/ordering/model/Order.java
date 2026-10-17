package com.ordering.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 订单实体 —— 对应数据库 orders 表。
 * 原样从原项目搬过来。
 */
public class Order implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private String orderNumber;
    private String userId;
    private String userName;
    private String userPhone;
    private String userAddress;
    private BigDecimal totalPrice;
    private Integer status;
    private Date createTime;
    private Date updateTime;
    private List<OrderItem> items;

    /** 订单状态常量。原来直接在代码里写 0/1/2/3/4/5，可读性差，
     *  用常量代替「魔法数字」是基本功。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_PREPARING = 2;
    public static final int STATUS_DELIVERED = 3;
    public static final int STATUS_COMPLETED = 4;
    public static final int STATUS_CANCELLED = 5;

    public Order() {
        this.items = new ArrayList<>();
        this.createTime = new Date();
        this.status = STATUS_PENDING;
    }

    public Order(String orderNumber, String userId, String userName,
                 String userPhone, String userAddress, BigDecimal totalPrice) {
        this();
        this.orderNumber = orderNumber;
        this.userId = userId;
        this.userName = userName;
        this.userPhone = userPhone;
        this.userAddress = userAddress;
        this.totalPrice = totalPrice;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserPhone() {
        return userPhone;
    }

    public void setUserPhone(String userPhone) {
        this.userPhone = userPhone;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public void addItem(OrderItem item) {
        items.add(item);
    }

    /**
     * 把状态数字翻译成中文，直接给页面用。
     * Thymeleaf 里写 ${order.statusText} 就会调用这个 getter。
     * 注意方法名叫 getStatusText，去掉 get 首字母小写就是属性名 statusText。
     */
    public String getStatusText() {
        if (status == null) return "未知状态";
        switch (status) {
            case STATUS_PENDING: return "待确认";
            case STATUS_CONFIRMED: return "已确认";
            case STATUS_PREPARING: return "制作中";
            case STATUS_DELIVERED: return "已配送";
            case STATUS_COMPLETED: return "已完成";
            case STATUS_CANCELLED: return "已取消";
            default: return "未知状态";
        }
    }
}
