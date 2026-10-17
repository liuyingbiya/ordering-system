package com.ordering.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 菜品实体类 —— 对应数据库里的 dish 表。
 *
 * 这个类基本是从原项目原样搬过来的，一行逻辑都没改。
 * 为什么不用改？因为它本来就是个「纯粹的数据容器」：
 * 一堆字段 + getter/setter，不依赖 Servlet 也不依赖任何框架。
 * 这种代码搬到哪儿都能用。
 *
 * 记住这个判断标准：搬家的时候，只有「跟容器/框架绑在一起」的代码才需要重写，
 * 纯粹的数据结构和业务算法是搬得走的。
 */
public class Dish implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 对应 dish.id */
    private Integer id;
    /** 对应 dish.name */
    private String name;
    /** 对应 dish.description */
    private String description;
    /** 对应 dish.price —— 金额用 BigDecimal，不用 double（浮点数算钱会丢精度） */
    private BigDecimal price;
    /** 对应 dish.category */
    private String category;
    /** 对应 dish.image_url */
    private String imageUrl;
    /** 页面展示用的表情图标。数据库里没有这一列，是给前端显示用的 */
    private String icon;
    /** 对应 dish.stock */
    private Integer stock;
    /** 对应 dish.create_time */
    private Date createTime;
    /** 对应 dish.active —— 是否上架。删除菜品用的是「软删除」：把 active 改成 false */
    private Boolean active;

    public Dish() {
    }

    public Dish(Integer id, String name, String description, BigDecimal price,
                String category, String imageUrl) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.imageUrl = imageUrl;
        this.icon = imageUrl;
        this.stock = 999;
        this.active = true;
        this.createTime = new Date();
    }

    public Dish(Integer id, String name, String description, BigDecimal price,
                String category, String imageUrl, Integer stock) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.imageUrl = imageUrl;
        this.icon = imageUrl;
        this.stock = stock;
        this.active = true;
        this.createTime = new Date();
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
