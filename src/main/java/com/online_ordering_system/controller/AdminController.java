package com.online_ordering_system.controller;

import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.domain.Restaurant;
import com.online_ordering_system.mapper.DishMapper;
import com.online_ordering_system.mapper.OrderReviewMapper;
import com.online_ordering_system.mapper.RestaurantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequiredArgsConstructor
public class AdminController {

    private final RestaurantMapper restaurantMapper;
    private final DishMapper dishMapper;
    private final OrderReviewMapper orderReviewMapper;

    // 1. 增加新的店铺
    @PostMapping("/restaurants")
    public String addRestaurant(@RequestBody Restaurant restaurant) {
        restaurant.setRestaurantId(UUID.randomUUID().toString().replace("-", ""));
        // 默认状态与基础评分
        restaurant.setRating(new java.math.BigDecimal("5.0"));
        restaurant.setAuditStatus("APPROVED");
        restaurantMapper.insert(restaurant);
        return "SUCCESS";
    }

    // 2. 增加店铺里新的菜品
    @PostMapping("/dishes")
    public String addDish(@RequestBody Dish dish) {
        dish.setDishId(UUID.randomUUID().toString().replace("-", ""));
        if (dish.getCurrentStock() == null) dish.setCurrentStock(0);
        if (dish.getSafetyStock() == null) dish.setSafetyStock(10);
        dishMapper.insert(dish);
        return "SUCCESS";
    }

    // 3. 增加店铺内菜品库存
    @PutMapping("/dishes/{dishId}/stock")
    public String increaseDishStock(@PathVariable String dishId, @RequestParam Integer addAmount) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish != null && addAmount != null && addAmount > 0) {
            dish.setCurrentStock(dish.getCurrentStock() + addAmount);
            dishMapper.updateById(dish);
            return "SUCCESS";
        }
        return "FAIL";
    }

    // 4. 删除用户评价
    @DeleteMapping("/reviews/{reviewId}")
    public String deleteReview(@PathVariable String reviewId) {
        orderReviewMapper.deleteById(reviewId);
        return "SUCCESS";
    }
}