package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order {
    @TableId
    private String orderId;
    private String orderNo;
    private String userId;
    private String restaurantId;
    private BigDecimal totalAmount;
    private String status;
    private String riderName;
    private String riderPhone;
    private LocalDateTime createdAt;
}
