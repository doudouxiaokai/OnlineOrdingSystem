package com.online_ordering_system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.domain.AgentRun;
import com.online_ordering_system.domain.AgentStep;
import com.online_ordering_system.llm.LlmClient;
import com.online_ordering_system.mapper.AgentRunMapper;
import com.online_ordering_system.mapper.AgentStepMapper;
import com.online_ordering_system.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Agent 编排服务（P1 版本：ReAct 反应式循环）。
 * P2 会在此基础上替换为「显式规划 + 拓扑执行 + 局部重规划」。
 * 每一步都落库，支持断点恢复与 Trace 回放。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final UserPrefService userPrefService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最大循环轮数，防止死循环 */
    private static final int MAX_ROUNDS = 10;

    /**
     * 系统提示词：定义 Agent 的角色与行为规则（与用户提供的 prompt 一致）。
     */
    private static final String SYSTEM_PROMPT =
            "你是外卖点餐助手。规则：\n" +
            "1. 对用户永远只用自然语言（店名、菜名），绝不提及任何 ID（restaurantId、dishId、orderId 等）。\n" +
            "2. 需要查询信息时，调用工具；工具返回的真实 ID 由系统内部使用，绝不对用户展示。\n" +
            "3. 严禁编造 ID：上下文还没有的 ID，说明对应查询步骤还没执行，先补齐再下单。\n" +
            "4. 用户说菜名但没选店时，先调 list_restaurants 引导选店。\n" +
            "5. 选过店后沿用，除非用户说换一家。\n" +
            "6. 需要用户身份时，直接用系统给你的当前用户 ID（见下方），不要自己编。\n" +
            "完成用户意图后，用自然语言总结结果。";

    /**
     * 执行一轮 Agent 对话，返回给前端的最终结果。
     */
    public Map<String, Object> run(String userId, String sessionId, String userText) {
        // 1. 创建 run 记录
        AgentRun run = new AgentRun();
        run.setRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setGoal(userText);
        run.setStatus("EXECUTING");
        run.setTokenUsed(0);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        agentRunMapper.insert(run);

        // 2. 构建消息列表（系统 + 用户身份 + 用户偏好记忆 + 用户）
        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = SYSTEM_PROMPT;
        if (userId != null && !userId.isEmpty()) {
            systemPrompt = systemPrompt + "\n当前用户 ID：" + userId;
        }
        String prefSummary = userPrefService.buildPreferenceSummary(userId);
        if (prefSummary != null && !prefSummary.isEmpty()) {
            systemPrompt = systemPrompt + "\n\n" + prefSummary;
        }
        messages.add(roleMsg("system", systemPrompt));
        messages.add(roleMsg("user", userText));

        int totalTokens = 0;
        int stepSeq = 0;
        String finalReply = null;

        try {
            // 3. ReAct 循环
            for (int round = 0; round < MAX_ROUNDS; round++) {
                LlmClient.LlmResponse response = llmClient.chat(messages, toolRegistry.toOpenAiTools());
                totalTokens += response.tokenUsed;

                if (!response.isToolCall) {
                    // LLM 直接给出最终回复
                    finalReply = response.content;
                    messages.add(roleMsg("assistant", response.content));
                    break;
                }

                // LLM 要调用工具
                for (LlmClient.ToolCall tc : response.toolCalls) {
                    stepSeq++;
                    Map<String, Object> args = parseArgs(tc.arguments);
                    AgentStep step = new AgentStep();
                    step.setStepId(UUID.randomUUID().toString().replace("-", ""));
                    step.setRunId(run.getRunId());
                    step.setSeq(stepSeq);
                    step.setToolName(tc.name);
                    step.setArgsJson(tc.arguments);
                    step.setStatus("RUNNING");
                    step.setAttempt(0);
                    step.setStartedAt(LocalDateTime.now());
                    agentStepMapper.insert(step);

                    // 执行工具
                    String toolResult;
                    String toolStatus = "DONE";
                    try {
                        Object result = toolRegistry.invoke(tc.name, args);
                        toolResult = objectMapper.writeValueAsString(result);
                    } catch (Exception e) {
                        toolResult = "{\"error\":\"" + escape(e.getMessage()) + "\"}";
                        toolStatus = "FAILED";
                    }

                    // 更新 step
                    step.setOutputJson(toolResult);
                    step.setStatus(toolStatus);
                    step.setFinishedAt(LocalDateTime.now());
                    step.setErrorMsg(toolStatus.equals("FAILED") ? toolResult : null);
                    agentStepMapper.updateById(step);

                    // 把工具调用和结果回填给 LLM
                    messages.add(roleMsg("assistant", null, tc.name, tc.arguments));
                    messages.add(roleMsg("tool", toolResult, tc.name, null));
                }
            }

            // 4. 收尾
            if (finalReply == null) {
                finalReply = "抱歉，我暂时无法完成这个请求，请换个说法试试。";
            }
            run.setStatus("DONE");
            run.setResultJson("{\"reply\":\"" + escape(finalReply) + "\"}");
            run.setTokenUsed(totalTokens);
            run.setUpdatedAt(LocalDateTime.now());
            agentRunMapper.updateById(run);

            Map<String, Object> result = new HashMap<>();
            result.put("type", "text");
            result.put("content", finalReply);
            result.put("runId", run.getRunId());
            result.put("steps", stepSeq);
            result.put("tokenUsed", totalTokens);
            return result;
        } catch (Exception e) {
            log.error("Agent 执行失败", e);
            run.setStatus("FAILED");
            run.setErrorMsg(e.getMessage());
            run.setTokenUsed(totalTokens);
            run.setUpdatedAt(LocalDateTime.now());
            agentRunMapper.updateById(run);

            Map<String, Object> result = new HashMap<>();
            result.put("type", "text");
            result.put("content", "抱歉，处理出错了：" + e.getMessage());
            return result;
        }
    }

    private Map<String, Object> roleMsg(String role, String content) {
        return roleMsg(role, content, null, null);
    }

    private Map<String, Object> roleMsg(String role, String content, String toolName, String toolArgs) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        if (content != null) m.put("content", content);
        if (toolName != null) {
            Map<String, Object> tc = new HashMap<>();
            tc.put("name", toolName);
            tc.put("arguments", toolArgs == null ? "{}" : toolArgs);
            m.put("tool_calls", Collections.singletonList(Map.of(
                    "id", "call_" + UUID.randomUUID().toString().substring(0, 8),
                    "type", "function",
                    "function", tc
            )));
        }
        if ("tool".equals(role) && toolName != null) {
            m.put("tool_call_id", "call_");
            m.put("name", toolName);
        }
        return m;
    }

    private Map<String, Object> parseArgs(String json) {
        try {
            if (json == null || json.isEmpty()) return new HashMap<>();
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
