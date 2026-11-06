package com.online_ordering_system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.agent.Plan;
import com.online_ordering_system.agent.PlanStep;
import com.online_ordering_system.service.PlannerOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 流式 WebSocket 处理器。
 * 客户端发送 {userId, sessionId, text}，服务端同步执行显式规划编排，
 * 并通过同一连接顺序推送事件：
 *   plan  —— 计划 DAG 结构（含 stepId/tool/dependsOn/status=PENDING）
 *   step  —— 每个步骤的开始(RUNNING)/完成(DONE)/失败(FAILED)
 *   done  —— 最终结果（content/runId/tokenUsed）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final PlannerOrchestrator plannerOrchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立：{}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, String> params = objectMapper.readValue(message.getPayload(), Map.class);
        String text = params.get("text");
        String userId = params.get("userId");
        String sessionId = params.get("sessionId");

        if (text == null || text.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("type", "error");
            err.put("message", "text 不能为空");
            send(session, err);
            return;
        }

        // 同步执行：编排全程在同一线程内，listener 里直接推送，天然保证事件顺序
        plannerOrchestrator.run(userId, sessionId, text, new SessionListener(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 连接关闭：{}，状态：{}", session.getId(), status);
    }

    private class SessionListener implements PlannerOrchestrator.OrchestrationListener {
        private final WebSocketSession session;

        SessionListener(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onPlanReady(Plan plan) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "plan");
            payload.put("runId", plan.getRunId());
            payload.put("plan", plan);
            send(session, payload);
        }

        @Override
        public void onStepStart(PlanStep step) {
            send(session, stepEvent(step, "RUNNING"));
        }

        @Override
        public void onStepDone(PlanStep step) {
            send(session, stepEvent(step, "DONE"));
        }

        @Override
        public void onStepFailed(PlanStep step, Exception e) {
            send(session, stepEvent(step, "FAILED"));
        }

        @Override
        public void onFinished(Map<String, Object> result) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "done");
            payload.put("runId", result.get("runId"));
            payload.put("content", result.get("content"));
            payload.put("tokenUsed", result.get("tokenUsed"));
            send(session, payload);
        }

        private Map<String, Object> stepEvent(PlanStep step, String status) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "step");
            payload.put("stepId", step.getStepId());
            payload.put("tool", step.getTool());
            payload.put("status", status);
            if (step.getOutput() != null) {
                payload.put("output", step.getOutput());
            }
            return payload;
        }
    }

    private void send(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.warn("WebSocket 推送失败：{}", e.getMessage());
        }
    }
}
