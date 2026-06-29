package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("t_order")
public class Order {

    @TableId
    private String orderId;
    private String orderNo;
    private String userId;
    private String restaurantId;
    private BigDecimal totalAmount;
    private String status; // UNPAID, PREPARING, DELIVERING, COMPLETED, CANCELLED
    private String address;
    private String riderName;
    private String riderPhone;

    // ===== 新增字段 START =====
    private String payMethod;         // 支付方式: WECHAT, ALIPAY, BANK_CARD
    private LocalDateTime paidAt;     // 支付时间
    private LocalDateTime expireTime;  // 支付过期时间 (10分钟后)
    private String cancelReason;      // 取消原因
    private LocalDateTime cancelledAt;// 取消时间
    // ===== 新增字段 END =====

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private List<OrderItem> items;
}