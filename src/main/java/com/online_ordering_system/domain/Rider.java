package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_rider")
public class Rider {
    @TableId
    private String riderId;
    private String name;
    private String phone;
    private String avatar;
    private String vehicleType;      // ELECTRIC / MOTOR / BIKE
    private BigDecimal rating;
    private Integer deliveredCount;
    private String status;           // OFFLINE / ONLINE / DELIVERING / REST
    private BigDecimal currentLng;
    private BigDecimal currentLat;
    private String currentOrderId;   // 当前配送中的订单ID，空闲为 null
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
