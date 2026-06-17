package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_restaurant")
public class Restaurant {
    @TableId
    private String restaurantId;
    private String name;
    private BigDecimal rating;
    private String address;
    private String auditStatus;
}
