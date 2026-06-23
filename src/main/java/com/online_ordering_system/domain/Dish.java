package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_dish")
public class Dish {
    @TableId
    private String dishId;
    private String restaurantId;
    private String name;
    private BigDecimal price;
    private String category;
    private Integer currentStock;
    private Integer safetyStock;
    private String description;
    private String image;
    private Integer sales;
    // 提高版动态定价扩展埋点
    public BigDecimal getPrice(BigDecimal weatherFactor) {
        if (weatherFactor != null) {
            return this.price.multiply(weatherFactor);
        }
        return this.price;
    }
}
