package com.online_ordering_system.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.Dish;
import com.online_ordering_system.llm.LlmClient;
import com.online_ordering_system.mapper.DishMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家侧 Agent（Merchant Agent）。
 * 复用低库存预警数据，用 LLM 生成补货建议单，与用户侧 Agent 通过「事件」协作，
 * 形成真正的多 agent 场景（用户侧点餐、商家侧备货）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MerchantAgent {

    private final DishMapper dishMapper;
    private final LlmClient llmClient;

    private static final String MERCHANT_PROMPT =
            "你是餐厅的补货助手。根据低库存菜品清单，生成补货建议。\n" +
            "对每个菜品给出：建议补货数量（补到安全库存的1.5倍左右）、紧急程度（高/中/低）。\n" +
            "输出纯文本，按行列出。";

    /**
     * 扫描低库存菜品并生成补货建议。
     */
    public List<Map<String, Object>> generateRestockAdvice() {
        List<Dish> lowStock = dishMapper.selectList(
                new LambdaQueryWrapper<Dish>()
                        .apply("current_stock < safety_stock")
        );
        if (lowStock.isEmpty()) {
            return List.of(Map.of("message", "当前无低库存菜品"));
        }

        // 拼装低库存清单喂给 LLM
        String inventory = lowStock.stream()
                .map(d -> d.getName() + "（当前库存" + d.getCurrentStock() + "，安全库存" + d.getSafetyStock() + "）")
                .collect(Collectors.joining("；"));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", MERCHANT_PROMPT));
        messages.add(Map.of("role", "user", "content", "低库存菜品：" + inventory));

        LlmClient.LlmResponse response = llmClient.chat(messages, null);

        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> m = new HashMap<>();
        m.put("lowStockCount", lowStock.size());
        m.put("advice", response.content);
        result.add(m);
        return result;
    }
}
