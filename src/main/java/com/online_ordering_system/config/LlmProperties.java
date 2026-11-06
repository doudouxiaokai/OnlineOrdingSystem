package com.online_ordering_system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 接入配置（OpenAI 兼容协议）。
 * 密钥通过环境变量 LLM_API_KEY 注入，绝不硬编码。
 * base-url / model 有默认值，可被 application.yml 或环境变量覆盖。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** OpenAI 兼容端点，默认阿里云百炼 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 模型名 */
    private String model = "qwen-plus";

    /** API Key，从环境变量 LLM_API_KEY 读取 */
    private String apiKey = "";

    /** 单次请求超时（秒） */
    private int timeoutSeconds = 30;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
