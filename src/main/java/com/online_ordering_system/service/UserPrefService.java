package com.online_ordering_system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.*;
import com.online_ordering_system.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户偏好服务（Agent 长期记忆）。
 * 从用户历史订单中抽取偏好（常点店、常点菜、平均客单价），
 * 供 Planner 在规划时前置注入，实现「第二次下单直接命中上次那家」。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserPrefService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RestaurantMapper restaurantMapper;
    private final DishMapper dishMapper;

    /**
     * 提取用户偏好摘要（注入 Planner 的上下文）。
     * 返回一段自然语言，描述该用户的常点店、常点菜、客单价。
     */
    public String buildPreferenceSummary(String userId) {
        if (userId == null || userId.isEmpty()) return "";

        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .ne(Order::getStatus, "CANCELLED")
                        .orderByDesc(Order::getCreatedAt)
        );
        if (orders.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("用户历史偏好：");

        // 常点店（按订单数统计）
        Map<String, Long> restaurantCount = orders.stream()
                .collect(Collectors.groupingBy(Order::getRestaurantId, Collectors.counting()));
        String favRestaurantId = restaurantCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (favRestaurantId != null) {
            Restaurant r = restaurantMapper.selectById(favRestaurantId);
            if (r != null) sb.append("最常点的店是「").append(r.getName()).append("」；");
        }

        // 常点菜（统计订单明细）
        List<String> orderIds = orders.stream().map(Order::getOrderId).collect(Collectors.toList());
        if (!orderIds.isEmpty()) {
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds)
            );
            Map<String, Long> dishCount = items.stream()
                    .collect(Collectors.groupingBy(OrderItem::getDishId, Collectors.counting()));
            String favDishId = dishCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (favDishId != null) {
                Dish d = dishMapper.selectById(favDishId);
                if (d != null) sb.append("最常点的菜是「").append(d.getName()).append("」；");
            }
        }

        // 平均客单价
        double avg = orders.stream()
                .mapToDouble(o -> o.getTotalAmount().doubleValue())
                .average().orElse(0);
        if (avg > 0) sb.append("平均客单价约 ").append(String.format("%.0f", avg)).append(" 元。");

        return sb.toString();
    }
}
