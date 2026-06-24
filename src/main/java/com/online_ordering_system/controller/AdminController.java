package com.online_ordering_system.controller;

import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.domain.Restaurant;
import com.online_ordering_system.domain.OrderReview;
import com.online_ordering_system.mapper.DishMapper;
import com.online_ordering_system.mapper.OrderReviewMapper;
import com.online_ordering_system.mapper.RestaurantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    // ===== 新增：管理后台专用查询接口 =====

    // 5. 获取所有店铺列表（用于选择）
    @GetMapping("/restaurants/list")
    public List<Restaurant> getAllRestaurants() {
        return restaurantMapper.selectList(null);
    }

    // 6. 获取指定店铺的菜品列表（用于选择）
    @GetMapping("/restaurants/{restaurantId}/dishes")
    public List<Dish> getDishesByRestaurant(@PathVariable String restaurantId) {
        return dishMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Dish>()
                        .eq(Dish::getRestaurantId, restaurantId)
        );
    }

    // 7. 获取所有评价列表（用于选择）
    @GetMapping("/reviews/list")
    public List<OrderReview> getAllReviews() {
        return orderReviewMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderReview>()
                        .orderByDesc(OrderReview::getCreatedAt)
        );
    }

    // 8. 获取所有菜品列表（用于库存管理选择）
    @GetMapping("/dishes/list")
    public List<Dish> getAllDishes() {
        return dishMapper.selectList(null);
    }
}