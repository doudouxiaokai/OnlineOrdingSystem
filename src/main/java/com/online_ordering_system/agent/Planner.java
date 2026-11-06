package com.online_ordering_system.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.llm.LlmClient;
import com.online_ordering_system.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划器：让 LLM 输出显式的 JSON 执行计划（DAG），而非盲目的 ReAct 单步跳转。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Planner {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final PlanValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PLANNER_PROMPT =
            "你是任务规划器。根据用户意图，拆解成一组可执行步骤，输出 JSON。\n" +
            "规则：\n" +
            "1. 每步包含：stepId(如s1)、tool(工具名)、args(参数对象)、dependsOn(依赖的前置stepId数组)、expect(该步骤成功应满足的断言)。\n" +
            "2. args 里可引用前一步结果，用 ${stepId.路径} 占位符：列表结果被包在 items 里，用 ${s1.items[0].restaurantId} 取第一个餐厅的 restaurantId；字段名严格用各工具「返回结构」里的驼峰名（restaurantId/dishId/orderId），不要写成 id。\n" +
            "3. 需要先查餐厅才能查菜单，先查菜单才能下单，依赖关系用 dependsOn 表达。\n" +
            "4. 只在真正需要时才安排步骤，不要多余步骤。\n" +
            "5. 用户没说店名时，必须先安排 search_restaurants 或 list_restaurants 查店拿到真实 restaurantId 再下单，绝不编造 restaurantId。\n" +
            "6. 涉及用户身份的参数（如 userId），一律使用系统提供的当前用户 ID，绝不自己编造。\n" +
            "7. 只输出 JSON，不要任何多余文字。\n" +
            "输出格式：{\"goal\":\"...\",\"steps\":[{\"stepId\":\"s1\",\"tool\":\"...\",\"args\":{...},\"dependsOn\":[],\"expect\":\"...\"},...]}";

    /** 最近一次 plan() 累计消耗的 token 数 */
    private int lastTokens = 0;

    public Plan plan(String goal, int maxRepair) {
        return plan(goal, null, maxRepair);
    }

    /**
     * 生成计划。返回 null 表示 LLM 未能产出合法计划。
     * @param extraContext 额外注入规划器的上下文（当前用户 ID、用户偏好记忆等），可为 null
     */
    public Plan plan(String goal, String extraContext, int maxRepair) {
        this.lastTokens = 0;
        String toolDesc = buildToolDescription();
        StringBuilder prompt = new StringBuilder(PLANNER_PROMPT);
        if (extraContext != null && !extraContext.isEmpty()) {
            prompt.append("\n").append(extraContext);
        }
        prompt.append("\n可用工具及其作用：\n").append(toolDesc);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompt.toString()));
        messages.add(Map.of("role", "user", "content", "用户意图：" + goal));

        for (int attempt = 0; attempt <= maxRepair; attempt++) {
            LlmClient.LlmResponse response = llmClient.chat(messages, null);
            lastTokens += response.tokenUsed;
            String content = extractJson(response.content);
            if (content == null) {
                log.warn("Planner 第{}次未输出合法JSON，重试", attempt + 1);
                messages.add(Map.of("role", "assistant", "content", response.content));
                messages.add(Map.of("role", "user", "content", "你上次的输出不是合法JSON，请只输出JSON。"));
                continue;
            }

            Plan plan = validator.validateAndRepair(content);
            if (plan != null) {
                plan.setGoal(goal);
                return plan;
            }
            log.warn("Planner 第{}次计划校验失败，重试", attempt + 1);
            messages.add(Map.of("role", "assistant", "content", response.content));
            messages.add(Map.of("role", "user", "content", "你上次的计划校验失败，请修正：确保工具名存在、参数正确、依赖无环。"));
        }
        return null;
    }

    public int getLastTokens() { return lastTokens; }

    /** 各工具的返回结构（供 LLM 生成正确的占位符引用） */
    private static final Map<String, String> TOOL_RETURN = Map.of(
            "list_restaurants", "[{restaurantId, name, rating, address}]（包装在 items 里）",
            "search_restaurants", "[{restaurantId, name, rating, address}]（包装在 items 里）",
            "list_dishes", "[{dishId, name, price, category, currentStock, sales, description}]（包装在 items 里）",
            "get_order_history", "[{orderId, restaurantId, totalAmount, status, createdAt}]（包装在 items 里）",
            "check_stock", "{dishId, name, currentStock, price}",
            "quote_order", "{totalAmount, items:[{dishId, name, quantity, subtotal}]}",
            "create_order", "{orderId, orderNo, totalAmount, status}",
            "pay_order", "{orderId, status}"
    );

    private String buildToolDescription() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> tool : toolRegistry.toOpenAiTools()) {
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            String name = (String) fn.get("name");
            sb.append("- ").append(name).append(": ").append(fn.get("description")).append("\n");
            String ret = TOOL_RETURN.get(name);
            if (ret != null) {
                sb.append("    返回结构: ").append(ret).append("\n");
            }
        }
        return sb.toString();
    }

    /** 从 LLM 输出中提取 JSON（容错：去掉可能的 ```json 包裹） */
    private String extractJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return null;
    }
}
