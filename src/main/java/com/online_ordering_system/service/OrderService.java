package com.online_ordering_system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.domain.Order;
import com.online_ordering_system.mapper.DishMapper;
import com.online_ordering_system.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final DishMapper dishMapper;
    private final OrderMapper orderMapper;

    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(String userId, String restaurantId, List<Map<String, Object>> items) {
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Map<String, Object> item : items) {
            String dishId = (String) item.get("dishId");
            Integer quantity = (Integer) item.get("quantity");

            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) throw new RuntimeException("菜品不存在");

            // 扣减本地库存 (利用MySQL行锁防止超卖)
            int updatedRows = dishMapper.update(null,
                    new LambdaUpdateWrapper<Dish>()
                            .eq(Dish::getDishId, dishId)
                            .ge(Dish::getCurrentStock, quantity)
                            .setSql("current_stock = current_stock - " + quantity)
            );

            if (updatedRows == 0) {
                throw new RuntimeException("菜品 [" + dish.getName() + "] 库存不足");
            }

            // 安全库存线预警检查
            Dish updatedDish = dishMapper.selectById(dishId);
            if (updatedDish.getCurrentStock() <= updatedDish.getSafetyStock()) {
                log.warn("【安全库存预警】菜品 {} 库存低于阈值，当前: {}", updatedDish.getName(), updatedDish.getCurrentStock());
            }

            totalAmount = totalAmount.add(dish.getPrice().multiply(new BigDecimal(quantity)));
        }

        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setRestaurantId(restaurantId);
        order.setTotalAmount(totalAmount);
        order.setStatus("PREPARING");
        order.setCreatedAt(LocalDateTime.now());

        orderMapper.insert(order);
        return order;
    }
}
