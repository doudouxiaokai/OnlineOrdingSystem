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
    private Integer quantity;
    private BigDecimal subtotal;

    // ✅ 新增：非数据库字段，用于前端显示菜品名称
    @TableField(exist = false)
    private String dishName;
}