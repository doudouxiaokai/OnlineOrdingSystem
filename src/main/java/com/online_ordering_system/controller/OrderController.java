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
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final RestaurantMapper restaurantMapper;
    private final UserAddressMapper userAddressMapper;
    private final OrderReviewMapper orderReviewMapper;

    /* ================= 工具方法（不新增文件） ================= */

    private ResponseEntity<Map<String, Object>> error(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }

    /* ================= 餐厅 & 菜品 ================= */

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

    /* ================= 订单创建（✅ 已修复） ================= */

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> params) {
        String userId = (String) params.get("userId");
        String restaurantId = (String) params.get("restaurantId");
        List<Map<String, Object>> items = (List<Map<String, Object>>) params.get("items");
        String address = (String) params.get("address");

        try {
            Order order = orderService.createOrder(userId, restaurantId, items, address);

            Map<String, Object> result = new HashMap<>();
            result.put("orderId", order.getOrderId());
            result.put("totalAmount", order.getTotalAmount());
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            // ✅ 库存不足 / 菜品不存在 都会走这里
            return error(e.getMessage());
        }
    }

    /* ================= 订单流转 ================= */

    @PostMapping("/orders/advance")
    public ResponseEntity<?> advanceOrder(@RequestBody Map<String, String> params) {
        try {
            Order order = orderService.transitionToNextStatus(params.get("orderId"));
            return ResponseEntity.ok(order);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/orders/list")
    public List<Order> listOrders(@RequestParam String userId) {
        return orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getCreatedAt)
        );
    }

    @GetMapping("/orders/track")
    public Order trackOrder(@RequestParam String orderId) {
        return orderMapper.selectById(orderId);
    }

    /* ================= 地址管理 ================= */

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

    /* ================= 评价 ================= */

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
    public List<Dish> getPersonalizedDishes(@RequestParam String userId) {
        return dishMapper.selectList(new LambdaQueryWrapper<Dish>().last("LIMIT 2"));
    }

}
