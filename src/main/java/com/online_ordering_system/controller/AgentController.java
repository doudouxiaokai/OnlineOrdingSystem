package com.online_ordering_system.controller;

import com.online_ordering_system.agent.CriticAgent;
import com.online_ordering_system.agent.MerchantAgent;
import com.online_ordering_system.eval.AgentEvaluator;
import com.online_ordering_system.service.PlannerOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 对话入口。
 * 前端只发 { sessionId, text }，编排逻辑全部在服务端完成。
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequiredArgsConstructor
public class AgentController {

    private final PlannerOrchestrator plannerOrchestrator;
    private final AgentEvaluator agentEvaluator;
    private final CriticAgent criticAgent;
    private final MerchantAgent merchantAgent;

    /**
     * 对话入口：一次同步调用，返回最终结果。
     * P3 起会改为 WebSocket 流式推送每个 step 的执行事件。
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> params) {
        String text = params.get("text");
        String sessionId = params.get("sessionId");
        String userId = params.get("userId");

        Map<String, Object> result = plannerOrchestrator.run(userId, sessionId, text);
        return ResponseEntity.ok(result);
    }

    /**
     * 评测入口：跑批 golden case，返回完成率等指标。
     */
    @GetMapping("/eval")
    public ResponseEntity<List<Map<String, Object>>> eval() {
        return ResponseEntity.ok(agentEvaluator.evaluate());
    }

    /**
     * Critic 复核入口：对下单方案做独立复核（多 agent 协作演示）。
     */
    @PostMapping("/critic")
    public ResponseEntity<Map<String, Object>> critic(@RequestBody Map<String, String> params) {
        String intent = params.get("intent");
        String plan = params.get("plan");
        CriticAgent.CriticResult result = criticAgent.review(intent, plan);
        return ResponseEntity.ok(Map.of(
                "pass", result.pass,
                "reason", result.reason
        ));
    }

    /**
     * 商家侧补货建议入口（多 agent 协作演示）。
     */
    @GetMapping("/merchant/restock")
    public ResponseEntity<List<Map<String, Object>>> merchantRestock() {
        return ResponseEntity.ok(merchantAgent.generateRestockAdvice());
    }
}
