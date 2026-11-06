package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 步骤记录（计划中的单个步骤执行情况）。
 * 是编排可回放、可恢复、可断言的最小单元。
 */
@Data
@TableName("t_agent_step")
public class AgentStep {
    @TableId
    private String stepId;
    private String runId;
    private Integer seq;            // 步骤序号
    private String toolName;        // 工具名
    private String argsJson;        // 入参（JSON）
    private String outputJson;      // 出参（JSON）
    private String expect;          // 断言描述
    private String status;          // PENDING/RUNNING/DONE/FAILED/SKIPPED
    private Integer attempt;        // 重试次数
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMsg;
}
