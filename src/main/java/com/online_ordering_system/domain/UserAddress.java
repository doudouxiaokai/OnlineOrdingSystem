package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_address")
public class UserAddress {
    @TableId
    private String addressId;
    private String userId;
    private String receiverName;
    private String receiverPhone;
    private String detailAddress;
}