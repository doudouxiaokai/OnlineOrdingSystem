package com.online_ordering_system.agent;

import com.online_ordering_system.tool.AgentTool;
import com.online_ordering_system.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 守护规则：在执行工具前做副作用分级检查与安全拦截。
 * - READ：放行
 * - WRITE：放行（可回滚）
 * - COMMIT：需检查是否触发人工确认（如大额下单）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Guardrails {

    private final ToolRegistry toolRegistry;

    /** 超过此金额（元）的下单需人工确认 */
    private static final double CONFIRM_THRESHOLD = 30.0;

    /**
     * 检查某工具调用是否放行。
     * @return null 表示放行；否则返回需人工确认的提示
     */
    public String check(String toolName, Object args) {
        ToolRegistry.ToolDefinition def = toolRegistry.get(toolName);
        if (def == null) return "未知工具：" + toolName;

        if (def.sideEffect == AgentTool.SideEffect.COMMIT) {
            // COMMIT 类操作：检查金额阈值（下单/支付）
            if (toolName.equals("create_order") || toolName.equals("place_order")) {
                Double amount = extractAmount(args);
                if (amount != null && amount > CONFIRM_THRESHOLD) {
                    return "订单金额 " + amount + " 元超过 " + CONFIRM_THRESHOLD + " 元，需要用户确认后才会执行";
                }
            }
        }

        if (def.confirmRequired) {
            return "该操作需要用户确认：" + toolName;
        }

        return null; // 放行
    }

    private Double extractAmount(Object args) {
        if (!(args instanceof java.util.Map)) return null;
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) args;
        Object amount = map.get("totalAmount");
        if (amount == null) amount = map.get("amount");
        if (amount == null) return null;
        try {
            return Double.parseDouble(String.valueOf(amount));
        } catch (Exception e) {
            return null;
        }
    }
}
