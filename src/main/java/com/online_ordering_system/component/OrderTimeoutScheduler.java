package com.online_ordering_system.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.Order;
import com.online_ordering_system.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTimeoutScheduler {

    private final OrderMapper orderMapper;

    @Scheduled(fixedDelay = 30000) // 每30秒扫描一次
    public void checkDeliveryExceptionOrders() {
        log.info("【系统定时任务】开始轮询检查全局异常挂死的外卖订单...");

        List<Order> exceptionOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getStatus, "PREPARING")
        );

        for (Order order : exceptionOrders) {
            order.setStatus("DELIVERY_EXCEPTION");
            orderMapper.updateById(order);
            log.error("🚨 【系统风控报警】订单流水号 {} 无人接单！状态已标记为 [DELIVERY_EXCEPTION]，请管理员立刻介入！",
                    order.getOrderNo());
        }
    }
}
