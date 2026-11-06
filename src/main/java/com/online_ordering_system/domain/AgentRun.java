package com.online_ordering_system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行记录（一次完整对话/任务的编排过程）。
 * 用于断点恢复、Trace 回放、成本统计。
 */
@Data
@TableName("t_agent_run")
public class AgentRun {
    @TableId
    private String runId;
    private String userId;
    private String sessionId;
    private String goal;            // 用户原始意图
    private String status;          // PENDING/PLANNING/EXECUTING/WAITING_HUMAN/DONE/FAILED
    private String planJson;        // 计划快照（JSON）
    private String resultJson;      // 最终结果（JSON）
    private String errorMsg;
    private Integer tokenUsed;      // 累计 token 消耗
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
