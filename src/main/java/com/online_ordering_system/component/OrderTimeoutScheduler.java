package com.online_ordering_system.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.online_ordering_system.domain.Order;
import com.online_ordering_system.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTimeoutScheduler {

    private final OrderMapper orderMapper;

    /**
     * ✅ 每 30 秒扫描
     * ✅ 只处理 10 分钟前仍未接单的订单
     */
    @Scheduled(fixedDelay = 30000)
    public void checkDeliveryExceptionOrders() {

        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(10);

        log.info("【定时任务】扫描超过 10 分钟未接单的订单，截止时间：{}", expireTime);

        orderMapper.update(
                null,
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getStatus, "PREPARING")
                        .lt(Order::getCreatedAt, expireTime)
                        .set(Order::getStatus, "DELIVERY_EXCEPTION")
        );

        // 只记录异常单（不要再循环 update）
        orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, "DELIVERY_EXCEPTION")
        ).forEach(order ->

                log.error("🚨 【风控报警】订单 {} 无人接单，已标记为异常",
                        order.getOrderNo())
        );
    }
}