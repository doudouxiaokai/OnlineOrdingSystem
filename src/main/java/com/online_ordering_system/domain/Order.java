package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order {

    @TableId(type = IdType.ASSIGN_UUID)
    private String orderId;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private String userId;

    @TableField("restaurant_id")
    private String restaurantId;

    @TableField("total_amount")
    private BigDecimal totalAmount;

    private String status;

    @TableField("rider_name")
    private String riderName;

    @TableField("rider_phone")
    private String riderPhone;

    private String address;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private java.util.List<OrderItem> items;
}