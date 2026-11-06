package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_order_item")
public class OrderItem {
    @TableId
    private String itemId;
    private String orderId;
    private String dishId;
    private String dishName;   // 下单时的菜品名快照，防止菜品改名/下架后订单明细显示"未知菜品"
    private Integer quantity;
    private BigDecimal subtotal;
}