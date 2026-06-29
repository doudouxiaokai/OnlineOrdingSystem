package com.online_ordering_system.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.domain.Order;
import com.online_ordering_system.mapper.DishMapper;
import com.online_ordering_system.mapper.OrderItemMapper;
import com.online_ordering_system.mapper.OrderMapper;
import com.online_ordering_system.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTimeoutScheduler {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderService orderService;
    private final DishMapper dishMapper; // ✅ 新增：注入菜品Mapper用于库存查询

    /**
     * ✅ 每5分钟扫描一次未接单订单
     * ✅ 新增事务注解：解决之前的非事务SqlSession日志冗余问题
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional(rollbackFor = Exception.class)
    public void checkDeliveryExceptionOrders() {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(10);

        log.info("【定时任务】扫描超过10分钟未接单的订单，截止时间：{}", expireTime);

        orderMapper.update(
                null,
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getStatus, "PREPARING")
                        .lt(Order::getCreatedAt, expireTime)
                        .set(Order::getStatus, "DELIVERY_EXCEPTION")
        );

        // 只记录异常单
        orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, "DELIVERY_EXCEPTION")
        ).forEach(order ->
                log.error("🚨 【风控报警】订单 {} 无人接单，已标记为异常", order.getOrderNo())
        );
    }

    /**
     * ✅ 每分钟扫描一次超时未支付订单
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional(rollbackFor = Exception.class)
    public void cancelTimeoutOrders() {
        log.info("【定时任务】扫描超时未支付订单...");

        List<Order> timeoutOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, "UNPAID")
                        .lt(Order::getExpireTime, LocalDateTime.now())
        );

        if (timeoutOrders.isEmpty()) {
            log.debug("未发现超时未支付订单");
            return;
        }

        for (Order order : timeoutOrders) {
            log.info("发现超时订单，准备取消：{}", order.getOrderNo());
            orderService.cancelOrder(order.getOrderId(), "系统自动取消（超时未支付）");
        }
        log.info("定时任务：共取消 {} 个超时订单", timeoutOrders.size());
    }

    /**
     * ✅ 新增：每10分钟扫描一次低库存菜品（符合你的需求）
     * ✅ 只读事务：避免非事务日志，同时提高查询效率
     */
    @Scheduled(fixedDelay = 600000) // 10分钟 = 600000毫秒
    @Transactional(readOnly = true)
    public void checkLowStockDishes() {
        log.info("【定时任务】开始扫描低库存菜品...");

        // 查询当前库存 < 安全库存的菜品
        List<Dish> lowStockDishes = dishMapper.selectList(
                new LambdaQueryWrapper<Dish>()
                        .apply("current_stock < safety_stock")
        );

        if (lowStockDishes.isEmpty()) {
            log.debug("未发现低库存菜品");
            return;
        }

        // 遍历输出库存预警日志
        lowStockDishes.forEach(dish ->
                log.warn("🚨 【库存预警】菜品「{}」库存不足，当前库存：{}，低于安全阈值：{}",
                        dish.getName(),
                        dish.getCurrentStock(),
                        dish.getSafetyStock())
        );
    }
}