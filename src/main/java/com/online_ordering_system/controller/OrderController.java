package com.online_ordering_system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.domain.Order;
import com.online_ordering_system.domain.Restaurant;
import com.online_ordering_system.mapper.DishMapper;
import com.online_ordering_system.mapper.OrderMapper;
import com.online_ordering_system.mapper.RestaurantMapper; // 1. 引入刚刚那个空的 RestaurantMapper
import com.online_ordering_system.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor // 自动为下方所有的 final 属性生成构造方法，实现自动注入
public class OrderController {

    private final DishMapper dishMapper;
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final RestaurantMapper restaurantMapper; // 2. 在这里声明它，Spring 会自动把它注入进来

    // 🌟 3. 补充缺少的餐厅列表接口（对应设计方案中的“浏览附近餐厅列表”功能）
    @GetMapping("/restaurants")
    public List<Restaurant> getRestaurants() {
        // 虽然 RestaurantMapper 是空的，但可以直接调用继承来的 selectList 方法
        // 传入一个空的 LambdaQueryWrapper 代表“没有任何条件，查询全表”
        return restaurantMapper.selectList(new LambdaQueryWrapper<>());
    }

    // 4. 访客/顾客 浏览特定餐厅的菜品详情
    @GetMapping("/dishes")
    public List<Dish> getDishes(@RequestParam String restaurantId) {
        return dishMapper.selectList(new LambdaQueryWrapper<Dish>().eq(Dish::getRestaurantId, restaurantId));
    }

    // 5. 顾客提单结算 (锁扣本地库存)
    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> params) {
        String userId = "u123";
        String restaurantId = (String) params.get("restaurantId");
        List<Map<String, Object>> items = (List<Map<String, Object>>) params.get("items");

        Order order = orderService.createOrder(userId, restaurantId, items);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getOrderId());
        result.put("totalAmount", order.getTotalAmount());
        return result;
    }

    // 6. 结算页个性化推荐菜品
    @GetMapping("/recommendations")
    public List<Dish> getPersonalizedDishes(@RequestParam String userId) {
        return dishMapper.selectList(new LambdaQueryWrapper<Dish>().last("LIMIT 2"));
    }

    // 7. 订单实时状态跟踪轮询
    @GetMapping("/orders/track")
    public Order trackOrder(@RequestParam String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && "PREPARING".equals(order.getStatus())) {
            order.setStatus("DELIVERING");
            order.setRiderName("顺丰同城专送-张兵");
            order.setRiderPhone("13888888888");
            orderMapper.updateById(order);
        }
        return order;
    }
}
