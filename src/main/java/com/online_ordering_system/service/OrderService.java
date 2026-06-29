package com.online_ordering_system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
     * 【修改点】创建订单时，状态设为 UNPAID，并设置10分钟后过期
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(String userId, String restaurantId, List<Map<String, Object>> items, String address, boolean needPay) {
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("订单商品不能为空");
        }

        BigDecimal totalAmount = BigDecimal.ZERO;

        // 1. 库存扣减逻辑完全不变（新老版本共用）
        for (Map<String, Object> item : items) {
            String dishId = (String) item.get("dishId");
            int quantity = ((Number) item.get("quantity")).intValue();

            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) throw new RuntimeException("菜品不存在");

            int updatedRows = dishMapper.update(null,
                    new LambdaUpdateWrapper<Dish>()
                            .eq(Dish::getDishId, dishId)
                            .ge(Dish::getCurrentStock, quantity)
                            .setSql("current_stock = current_stock - " + quantity  + ", sales = sales + " + quantity)
            );

            if (updatedRows == 0) {
                throw new RuntimeException("菜品 [" + dish.getName() + "] 库存不足");
            }

            totalAmount = totalAmount.add(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        // 2. 创建订单（根据needPay决定状态）
        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString().replace("-", ""));
        order.setOrderNo("ORD" + System.currentTimeMillis());
        order.setUserId(userId);
        order.setRestaurantId(restaurantId);
        order.setTotalAmount(totalAmount);
        order.setAddress(address);
        order.setCreatedAt(LocalDateTime.now());

        if (needPay) {
            // 【新版本逻辑】走支付流程，状态为UNPAID，设置10分钟过期
            order.setStatus("UNPAID");
            order.setExpireTime(LocalDateTime.now().plusMinutes(10));
            log.info("创建待支付订单成功：{}, 过期时间：{}", order.getOrderNo(), order.getExpireTime());
        } else {
            // 【老版本兼容逻辑】自动支付，直接到商家备餐状态
            order.setStatus("PREPARING");
            order.setPayMethod("LEGACY_AUTO"); // 标记为老版本自动支付，方便后续统计
            order.setPaidAt(LocalDateTime.now());
            log.info("老版本自动支付订单成功：{}, 直接到商家备餐状态", order.getOrderNo());
        }

        orderMapper.insert(order);

        // 3. 保存订单项逻辑完全不变
        for (Map<String, Object> itemReq : items) {
            String dishId = (String) itemReq.get("dishId");
            int quantity = ((Number) itemReq.get("quantity")).intValue();
            Dish dish = dishMapper.selectById(dishId);

            OrderItem orderItem = new OrderItem();
            orderItem.setItemId(UUID.randomUUID().toString().replace("-", ""));
            orderItem.setOrderId(order.getOrderId());
            orderItem.setDishId(dishId);
            orderItem.setQuantity(quantity);
            orderItem.setSubtotal(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));

            orderItemMapper.insert(orderItem);
        }

        return order;
    }

    /**
     * 【新增】模拟支付逻辑
     */
    @Transactional(rollbackFor = Exception.class)
    public Order payOrder(String orderId, String payMethod) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在");
        }

        // 检查是否超时
        if (LocalDateTime.now().isAfter(order.getExpireTime())) {
            // 如果是超时导致的支付，自动转为取消状态
            this.cancelOrder(orderId, "支付超时");
            throw new IllegalStateException("订单已超时，请重新下单");
        }

        if (!"UNPAID".equals(order.getStatus())) {
            throw new IllegalStateException("订单状态异常，无法支付");
        }

        // 模拟支付成功
        log.info("模拟支付成功：订单{}，支付方式：{}", orderId, payMethod);
        order.setStatus("PREPARING"); // 支付完成后进入商家做饭阶段
        order.setPayMethod(payMethod);
        order.setPaidAt(LocalDateTime.now());
        orderMapper.updateById(order);

        return order;
    }

    /**
     * 【新增】取消订单逻辑 (回滚库存)
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderId, String reason) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在");
        }

        // 只有待支付和已支付但未发货的可以取消（这里简化为只要没完成就能取消，实际业务会更复杂）
        if ("COMPLETED".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus())) {
            return; // 已结束的订单不再处理
        }

        log.info("取消订单：{}，原因：{}", orderId, reason);

        // 恢复库存
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId)
        );

        for (OrderItem item : items) {
            dishMapper.update(null,
                    new LambdaUpdateWrapper<Dish>()
                            .eq(Dish::getDishId, item.getDishId())
                            .setSql("current_stock = current_stock + " + item.getQuantity())
            );
        }

        // 更新订单状态
        order.setStatus("CANCELLED");
        order.setCancelReason(reason);
        order.setCancelledAt(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * 【已移除】定时任务已移至 OrderTimeoutScheduler 组件
     * 保留此方法供 Scheduler 调用
     */

    @Transactional(rollbackFor = Exception.class)
    public Order transitionToNextStatus(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");

        // 如果是UNPAID状态，不允许直接流转（必须先支付）
        if ("UNPAID".equals(order.getStatus())) {
            throw new RuntimeException("订单尚未支付，请先完成支付");
        }

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
            restaurant.setRating(new BigDecimal(avg).setScale(1, BigDecimal.ROUND_HALF_UP));
            restaurantMapper.updateById(restaurant);
        }
    }
}