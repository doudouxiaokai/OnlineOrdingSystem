package com.online_ordering_system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.*;
import com.online_ordering_system.mapper.*;
import com.online_ordering_system.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE
})
@RequiredArgsConstructor
public class OrderController {

    private final DishMapper dishMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final RestaurantMapper restaurantMapper;
    private final UserAddressMapper userAddressMapper;
    private final OrderReviewMapper orderReviewMapper;

    private ResponseEntity<Map<String, Object>> error(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    @GetMapping("/restaurants")
    public List<Restaurant> getRestaurants() {
        return restaurantMapper.selectList(new LambdaQueryWrapper<>());
    }

    @GetMapping("/dishes")
    public List<Dish> getDishes(@RequestParam String restaurantId) {
        return dishMapper.selectList(
                new LambdaQueryWrapper<Dish>().eq(Dish::getRestaurantId, restaurantId)
        );
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> params) {
        try {
            String userId = (String) params.get("userId");
            String restaurantId = (String) params.get("restaurantId");
            String address = (String) params.get("address");
            List<Map<String, Object>> items = (List<Map<String, Object>>) params.get("items");

            List<Map<String, Object>> formattedItems = new java.util.ArrayList<>();
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Map<String, Object> formattedItem = new java.util.HashMap<>();
                    formattedItem.put("dishId", item.get("dishId"));
                    Object qtyObj = item.get("quantity");
                    if (qtyObj == null) qtyObj = item.get("count");
                    int quantity = qtyObj != null ? Integer.parseInt(qtyObj.toString()) : 1;
                    formattedItem.put("quantity", quantity);
                    formattedItems.add(formattedItem);
                }
            }

            Order savedOrder = orderService.createOrder(userId, restaurantId, formattedItems, address);
            Map<String, Object> result = new HashMap<>();
            result.put("orderId", savedOrder.getOrderId());
            result.put("orderNo", savedOrder.getOrderNo());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return error("下单失败：" + e.getMessage());
        }
    }

    @PostMapping("/orders/advance")
    public ResponseEntity<?> advanceOrder(@RequestBody Map<String, String> params) {
        try {
            Order order = orderService.transitionToNextStatus(params.get("orderId"));
            return ResponseEntity.ok(order);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /* 订单列表：彻底移除明细，只返回基础信息 */
    @GetMapping("/orders/list")
    public List<Map<String, Object>> listOrders(@RequestParam String userId) {
        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getCreatedAt)
        );

        List<Map<String, Object>> resultList = new java.util.ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> orderMap = new java.util.HashMap<>();
            orderMap.put("orderId", order.getOrderId());
            orderMap.put("orderNo", order.getOrderNo());
            orderMap.put("status", order.getStatus());
            orderMap.put("createdAt", order.getCreatedAt());
            orderMap.put("totalAmount", order.getTotalAmount());
            orderMap.put("restaurantId", order.getRestaurantId());
            resultList.add(orderMap);
        }
        return resultList;
    }

    /* 订单跟踪：返回明细+菜品名，适配前端track.vue */
    @GetMapping("/orders/track")
    public Order trackOrder(@RequestParam String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) return null;
        List<OrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId)
        );
        for (OrderItem item : orderItems) {
            Dish dish = dishMapper.selectById(item.getDishId());
            item.setDishName(dish != null ? dish.getName() : "未知菜品");
        }
        order.setItems(orderItems);
        return order;
    }

    @GetMapping("/address")
    public List<UserAddress> getUserAddresses(@RequestParam String userId) {
        return userAddressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId)
        );
    }

    @PostMapping("/address/save")
    public String saveAddress(@RequestBody UserAddress userAddress) {
        if (userAddress.getAddressId() == null || userAddress.getAddressId().isEmpty()) {
            userAddress.setAddressId(java.util.UUID.randomUUID().toString().replace("-", ""));
            userAddressMapper.insert(userAddress);
        } else {
            userAddressMapper.updateById(userAddress);
        }
        return "SUCCESS";
    }

    @PostMapping("/reviews")
    public String submitReview(@RequestBody OrderReview review) {
        orderService.submitReview(review);
        return "SUCCESS";
    }

    @GetMapping("/reviews/restaurant")
    public List<OrderReview> getRestaurantReviews(@RequestParam String restaurantId) {
        return orderReviewMapper.selectList(
                new LambdaQueryWrapper<OrderReview>()
                        .eq(OrderReview::getRestaurantId, restaurantId)
                        .orderByDesc(OrderReview::getCreatedAt)
        );
    }

    @GetMapping("/recommendations")
    public List<Dish> getPersonalizedDishes(
            @RequestParam String userId,
            @RequestParam String restaurantId) {

        return dishMapper.selectList(
                new LambdaQueryWrapper<Dish>()
                        .eq(Dish::getRestaurantId, restaurantId)
                        .last("ORDER BY RAND() LIMIT 2")
        );
    }

    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> deleteOrder(@PathVariable String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) return error("订单不存在");
        orderMapper.deleteById(orderId);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "订单删除成功");
        return ResponseEntity.ok(result);
    }
}