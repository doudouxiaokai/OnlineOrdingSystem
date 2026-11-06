package com.online_ordering_system.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.domain.Order;
import com.online_ordering_system.domain.Restaurant;
import com.online_ordering_system.mapper.DishMapper;
import com.online_ordering_system.mapper.OrderMapper;
import com.online_ordering_system.mapper.RestaurantMapper;
import com.online_ordering_system.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 点餐领域工具集合。
 * 这些方法会被 ToolRegistry 扫描注册，供 LLM 通过 function calling 调用。
 * 方法签名统一为：接收 Map<String,Object>，返回 Object（会被序列化回填给 LLM）。
 */
@Component
@RequiredArgsConstructor
public class OrderingTools {

    private final RestaurantMapper restaurantMapper;
    private final DishMapper dishMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 从 args 里按多个候选 key 取字符串值（兼容下划线/驼峰两种参数名） */
    private static String argStr(Map<String, Object> args, String... keys) {
        for (String k : keys) {
            Object v = args.get(k);
            if (v != null && !v.toString().isEmpty()) return v.toString();
        }
        return null;
    }

    /**
     * 查看餐厅列表。
     */
    @AgentTool(
            name = "list_restaurants",
            description = "查看可点餐的餐厅列表，返回餐厅名、评分、地址等信息",
            sideEffect = AgentTool.SideEffect.READ
    )
    public List<Map<String, Object>> listRestaurants(Map<String, Object> args) {
        List<Restaurant> restaurants = restaurantMapper.selectList(
                new LambdaQueryWrapper<Restaurant>()
                        .eq(Restaurant::getAuditStatus, "APPROVED")
        );
        return restaurants.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("restaurantId", r.getRestaurantId());
            m.put("name", r.getName());
            m.put("rating", r.getRating());
            m.put("address", r.getAddress());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 查看某家餐厅的菜单。
     * 入参支持 restaurantName（自然语言店名）或 restaurantId，内部统一翻译成 restaurantId。
     * 这解决了「agent 用店名、后端接口用 ID」的关键坑。
     */
    @AgentTool(
            name = "list_dishes",
            description = "查看某家餐厅的菜单，返回菜品名、价格、库存、销量、是否招牌菜等信息",
            sideEffect = AgentTool.SideEffect.READ,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"restaurant_name\":{\"type\":\"string\",\"description\":\"餐厅名\"}},\"required\":[\"restaurant_name\"]}"
    )
    public List<Map<String, Object>> listDishes(Map<String, Object> args) {
        String restaurantName = argStr(args, "restaurant_name", "name");
        if (restaurantName == null) restaurantName = "";
        String restaurantId = argStr(args, "restaurantId", "restaurant_id");

        if (restaurantId == null || restaurantId.isEmpty()) {
            // 按店名翻译成 restaurantId（精确匹配优先，模糊返回第一个）
            Restaurant matched = resolveRestaurantByName(restaurantName);
            if (matched == null) {
                throw new RuntimeException("未找到餐厅：" + restaurantName + "，请先调用 list_restaurants 确认店名");
            }
            restaurantId = matched.getRestaurantId();
        }

        List<Dish> dishes = dishMapper.selectList(
                new LambdaQueryWrapper<Dish>().eq(Dish::getRestaurantId, restaurantId)
        );
        return dishes.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("dishId", d.getDishId());
            m.put("name", d.getName());
            m.put("price", d.getPrice());
            m.put("category", d.getCategory());
            m.put("currentStock", d.getCurrentStock());
            m.put("sales", d.getSales());
            m.put("description", d.getDescription());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 按店名解析餐厅（精确匹配优先，模糊匹配兜底）。
     */
    private Restaurant resolveRestaurantByName(String name) {
        if (name == null || name.isEmpty()) return null;
        // 精确匹配
        Restaurant exact = restaurantMapper.selectOne(
                new LambdaQueryWrapper<Restaurant>().eq(Restaurant::getName, name)
        );
        if (exact != null) return exact;
        // 模糊匹配（包含）
        List<Restaurant> fuzzy = restaurantMapper.selectList(
                new LambdaQueryWrapper<Restaurant>().like(Restaurant::getName, name)
        );
        return fuzzy.isEmpty() ? null : fuzzy.get(0);
    }

    /**
     * 提交订单（COMMIT 副作用，Guardrails 会检查金额阈值）。
     * 入参：userId, restaurantId(或 restaurant_name), dishes[{dish_id, quantity}], address
     */
    @AgentTool(
            name = "create_order",
            description = "提交订单。需要提供用户、餐厅、菜品列表（dish_id+数量）和收货地址",
            sideEffect = AgentTool.SideEffect.COMMIT,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\"},\"restaurantId\":{\"type\":\"string\"},\"dishes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"dish_id\":{\"type\":\"string\"},\"quantity\":{\"type\":\"integer\"}}}},\"address\":{\"type\":\"string\"}},\"required\":[\"userId\",\"restaurantId\",\"dishes\"]}"
    )
    public Map<String, Object> createOrder(Map<String, Object> args) {
        String userId = argStr(args, "userId", "user_id");
        if (userId == null) userId = "default_user_001";
        String restaurantId = argStr(args, "restaurantId", "restaurant_id");
        String address = argStr(args, "address");
        if (address == null) address = "默认地址";
        List<Map<String, Object>> dishes = (List<Map<String, Object>>) (args.get("dishes") != null ? args.get("dishes") : args.get("items"));

        if (dishes == null || dishes.isEmpty()) {
            throw new RuntimeException("下单需要至少一个菜品");
        }

        // 把 dish_id 转成后端接口需要的 dishId 字段
        List<Map<String, Object>> items = dishes.stream().map(d -> {
            Map<String, Object> item = new HashMap<>();
            item.put("dishId", d.getOrDefault("dish_id", d.get("dishId")));
            item.put("quantity", d.getOrDefault("quantity", 1));
            return item;
        }).collect(Collectors.toList());

        Order order = orderService.createOrder(userId, restaurantId, items, address, true);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getOrderId());
        result.put("orderNo", order.getOrderNo());
        result.put("totalAmount", order.getTotalAmount());
        result.put("status", order.getStatus());
        return result;
    }

    /**
     * 查询用户历史订单（用于"上次那家"这类记忆需求）。
     */
    @AgentTool(
            name = "get_order_history",
            description = "查询用户的历史订单，返回最近下单的餐厅和菜品，用于识别用户偏好",
            sideEffect = AgentTool.SideEffect.READ,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"userId\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"userId\"]}"
    )
    public List<Map<String, Object>> getOrderHistory(Map<String, Object> args) {
        String userId = argStr(args, "userId", "user_id");
        int limit = args.get("limit") instanceof Number ? ((Number) args.get("limit")).intValue() : 5;

        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .ne(Order::getStatus, "CANCELLED")
                        .orderByDesc(Order::getCreatedAt)
                        .last("LIMIT " + limit)
        );
        return orders.stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            m.put("orderId", o.getOrderId());
            m.put("restaurantId", o.getRestaurantId());
            m.put("totalAmount", o.getTotalAmount());
            m.put("status", o.getStatus());
            m.put("createdAt", String.valueOf(o.getCreatedAt()));
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 查询菜品库存（用于下单前的库存断言）。
     */
    @AgentTool(
            name = "check_stock",
            description = "查询指定菜品的当前库存，用于判断是否可下单",
            sideEffect = AgentTool.SideEffect.READ,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"dishId\":{\"type\":\"string\"}},\"required\":[\"dishId\"]}"
    )
    public Map<String, Object> checkStock(Map<String, Object> args) {
        String dishId = argStr(args, "dishId", "dish_id");
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) throw new RuntimeException("菜品不存在：" + dishId);
        Map<String, Object> m = new HashMap<>();
        m.put("dishId", dish.getDishId());
        m.put("name", dish.getName());
        m.put("currentStock", dish.getCurrentStock());
        m.put("price", dish.getPrice());
        return m;
    }

    /**
     * 报价（不扣库存）：计算订单总价，供下单前校验预算。
     */
    @AgentTool(
            name = "quote_order",
            description = "计算订单总价（不扣库存），用于下单前校验是否超预算",
            sideEffect = AgentTool.SideEffect.READ,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"dishes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"dish_id\":{\"type\":\"string\"},\"quantity\":{\"type\":\"integer\"}}}}},\"required\":[\"dishes\"]}"
    )
    public Map<String, Object> quoteOrder(Map<String, Object> args) {
        List<Map<String, Object>> dishes = (List<Map<String, Object>>) (args.get("dishes") != null ? args.get("dishes") : args.get("items"));
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> detail = new java.util.ArrayList<>();
        for (Map<String, Object> d : dishes) {
            String dishId = (String) d.getOrDefault("dish_id", d.get("dishId"));
            int qty = d.get("quantity") instanceof Number ? ((Number) d.get("quantity")).intValue() : 1;
            Dish dish = dishMapper.selectById(dishId);
            if (dish == null) throw new RuntimeException("菜品不存在：" + dishId);
            BigDecimal subtotal = dish.getPrice().multiply(BigDecimal.valueOf(qty));
            total = total.add(subtotal);
            Map<String, Object> item = new HashMap<>();
            item.put("dishId", dishId);
            item.put("name", dish.getName());
            item.put("quantity", qty);
            item.put("subtotal", subtotal);
            detail.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("totalAmount", total);
        result.put("items", detail);
        return result;
    }

    /**
     * 支付订单（COMMIT 副作用）。
     */
    @AgentTool(
            name = "pay_order",
            description = "支付指定订单",
            sideEffect = AgentTool.SideEffect.COMMIT,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"payMethod\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}"
    )
    public Map<String, Object> payOrder(Map<String, Object> args) {
        String orderId = argStr(args, "orderId", "order_id");
        String payMethod = (String) args.getOrDefault("payMethod", "WECHAT");
        Order order = orderService.payOrder(orderId, payMethod);
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getOrderId());
        result.put("status", order.getStatus());
        return result;
    }

    /**
     * 按条件搜索餐厅（支持只搜营业中）。
     */
    @AgentTool(
            name = "search_restaurants",
            description = "按条件搜索餐厅，支持只返回营业中的店",
            sideEffect = AgentTool.SideEffect.READ,
            parametersJson = "{\"type\":\"object\",\"properties\":{\"openOnly\":{\"type\":\"boolean\"},\"keyword\":{\"type\":\"string\"}},\"required\":[]}"
    )
    public List<Map<String, Object>> searchRestaurants(Map<String, Object> args) {
        LambdaQueryWrapper<Restaurant> wrapper = new LambdaQueryWrapper<Restaurant>()
                .eq(Restaurant::getAuditStatus, "APPROVED");
        String keyword = argStr(args, "keyword", "name");
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(Restaurant::getName, keyword);
        }
        List<Restaurant> restaurants = restaurantMapper.selectList(wrapper);
        return restaurants.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("restaurantId", r.getRestaurantId());
            m.put("name", r.getName());
            m.put("rating", r.getRating());
            m.put("address", r.getAddress());
            return m;
        }).collect(Collectors.toList());
    }
}
