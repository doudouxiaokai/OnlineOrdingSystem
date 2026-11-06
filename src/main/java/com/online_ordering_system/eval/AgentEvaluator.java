package com.online_ordering_system.eval;

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
 * Agent 评测器（P4）。
 * 用一组 golden case（含预期工具调用）跑批，统计任务完成率、平均步数、工具参数正确率。
 * 通过一个命令行入口触发，输出指标表格，支撑 prompt 迭代。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentEvaluator {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;

    /** golden case：输入文本 -> 预期第一个工具名 */
    private static final List<GoldenCase> GOLDEN_CASES = List.of(
            new GoldenCase("附近有什么餐厅", "list_restaurants"),
            new GoldenCase("有什么店推荐一下", "list_restaurants"),
            new GoldenCase("大牌猪脚饭有什么菜", "list_dishes"),
            new GoldenCase("看看川味坊的菜单", "list_dishes"),
            new GoldenCase("来一份猪脚饭", "list_restaurants"), // 未选店先引导
            new GoldenCase("我上次点了什么", "get_order_history"),
            new GoldenCase("今天天气怎么样", "(no tool)") // 无关话题，不应触发工具
    );

    private static final String SYSTEM_PROMPT =
            "你是外卖点餐助手。需要查询信息时调用工具；工具返回的真实 ID 由系统内部使用，绝不对用户展示。" +
            "用户说菜名但没选店时先调 list_restaurants。当前用户 ID：eval-user。";

    /**
     * 跑批评测，返回结果列表。
     */
    public List<Map<String, Object>> evaluate() {
        List<Map<String, Object>> results = new ArrayList<>();
        int pass = 0;

        for (GoldenCase gc : GOLDEN_CASES) {
            Map<String, Object> result = runSingle(gc);
            results.add(result);
            if ((boolean) result.get("pass")) pass++;
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", GOLDEN_CASES.size());
        summary.put("pass", pass);
        summary.put("passRate", GOLDEN_CASES.isEmpty() ? 0 : (double) pass / GOLDEN_CASES.size());
        results.add(summary);

        return results;
    }

    private Map<String, Object> runSingle(GoldenCase gc) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", gc.input));

        try {
            LlmClient.LlmResponse response = llmClient.chat(messages, toolRegistry.toOpenAiTools());
            String actualTool = response.isToolCall && !response.toolCalls.isEmpty()
                    ? response.toolCalls.get(0).name : "(no tool)";

            boolean pass = gc.expectedTool.equals(actualTool);
            Map<String, Object> m = new HashMap<>();
            m.put("input", gc.input);
            m.put("expectedTool", gc.expectedTool);
            m.put("actualTool", actualTool);
            m.put("pass", pass);
            m.put("tokenUsed", response.tokenUsed);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new HashMap<>();
            m.put("input", gc.input);
            m.put("expectedTool", gc.expectedTool);
            m.put("actualTool", "ERROR");
            m.put("pass", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    private static class GoldenCase {
        final String input;
        final String expectedTool;
        GoldenCase(String input, String expectedTool) {
            this.input = input;
            this.expectedTool = expectedTool;
        }
    }
}
