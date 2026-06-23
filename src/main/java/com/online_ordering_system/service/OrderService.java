package com.online_ordering_system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.online_ordering_system.domain.*;
import com.online_ordering_system.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final DishMapper dishMapper;
    private final OrderMapper orderMapper;
    private final RestaurantMapper restaurantMapper;
    private final OrderReviewMapper orderReviewMapper;
    private final OrderItemMapper orderItemMapper;

    private final List<Map<String, String>> VIRTUAL_RIDERS = Arrays.asList(
            new HashMap<String, String>() {{ put("name", "张小哥"); put("phone", "13911112222"); }},
            new HashMap<String, String>() {{ put("name", "王大叔"); put("phone", "13688889999"); }}
    );

    /**
     * 创建订单 (Java 8 规范：支持前端传入的自选 address)
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(String userId, String restaurantId, List<Map<String, Object>> items, String address) {
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("订单商品不能为空");
        }

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Map<String, Object> item : items) {
            String dishId = (String) item.get("dishId");
            int quantity = ((Number) item.get("quantity")).intValue();

            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) throw new RuntimeException("菜品不存在");

            int updatedRows = dishMapper.update(null,
                    new LambdaUpdateWrapper<Dish>()
                            .eq(Dish::getDishId, dishId)
                            .ge(Dish::getCurrentStock, quantity)
                            .setSql("current_stock = current_stock - " + quantity)
            );

            if (updatedRows == 0) {
                throw new RuntimeException("菜品 [" + dish.getName() + "] 库存不足");
            }

            totalAmount = totalAmount.add(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString().replace("-", ""));
        order.setOrderNo(String.valueOf(System.currentTimeMillis()));
        order.setUserId(userId);
        order.setRestaurantId(restaurantId);
        order.setTotalAmount(totalAmount);
        order.setStatus("PREPARING");
        order.setAddress(address);
        order.setCreatedAt(LocalDateTime.now());

        orderMapper.insert(order);

        for (Map<String, Object> itemReq : items) {
            String dishId = (String) itemReq.get("dishId");
            int quantity = ((Number) itemReq.get("quantity")).intValue();
            Dish dish = dishMapper.selectById(dishId);

            OrderItem orderItem = new OrderItem();
            orderItem.setItemId(UUID.randomUUID().toString().replace("-", ""));
            orderItem.setOrderId(order.getOrderId()); // 关键修复：绑定主订单ID
            orderItem.setDishId(dishId);
            orderItem.setQuantity(quantity);
            orderItem.setSubtotal(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));

            orderItemMapper.insert(orderItem);
        }
        return order;
    }


    @Transactional(rollbackFor = Exception.class)
    public Order transitionToNextStatus(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");

        String currentStatus = order.getStatus();
        if ("PREPARING".equals(currentStatus)) {
            order.setStatus("DELIVERING");
            int randomIndex = new Random().nextInt(VIRTUAL_RIDERS.size());
            Map<String, String> selectedRider = VIRTUAL_RIDERS.get(randomIndex);
            order.setRiderName(selectedRider.get("name"));
            order.setRiderPhone(selectedRider.get("phone"));
        } else if ("DELIVERING".equals(currentStatus)) {
            order.setStatus("COMPLETED");
        } else {
            throw new RuntimeException("当前订单状态已完结，无法继续流转");
        }

        orderMapper.updateById(order);
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public void submitReview(OrderReview review) {
        Order order = orderMapper.selectById(review.getOrderId());
        if (order == null || !"COMPLETED".equals(order.getStatus())) {
            throw new RuntimeException("只有已完成的订单才能进行评价");
        }

        review.setReviewId(UUID.randomUUID().toString().replace("-", ""));
        review.setCreatedAt(LocalDateTime.now());
        orderReviewMapper.insert(review);

        List<OrderReview> reviews = orderReviewMapper.selectList(
                new LambdaQueryWrapper<OrderReview>().eq(OrderReview::getRestaurantId, review.getRestaurantId())
        );
        if (!reviews.isEmpty()) {
            double sum = reviews.stream().mapToInt(OrderReview::getRating).sum();
            double avg = sum / reviews.size();

            Restaurant restaurant = new Restaurant();
            restaurant.setRestaurantId(review.getRestaurantId());

            // ✅ 纯正 Java 8 写法：保留你的 BigDecimal.ROUND_HALF_UP，通过 new BigDecimal(double) 解决类型转换问题
            restaurant.setRating(new BigDecimal(avg).setScale(1, BigDecimal.ROUND_HALF_UP));
            restaurantMapper.updateById(restaurant);
        }
    }
}