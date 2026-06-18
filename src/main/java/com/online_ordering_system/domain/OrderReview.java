package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_review")
public class OrderReview {
    @TableId
    private String reviewId;
    private String orderId;
    private String restaurantId;
    private String userId;
    private String username;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
}