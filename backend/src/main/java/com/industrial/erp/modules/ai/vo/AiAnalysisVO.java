package com.industrial.erp.modules.ai.vo;

/**
 * v1.1.75 AI 分析结果.
 *
 * <p>只读 demo: 把某张销售出库单的字段 + 明细喂给 LLM, 返回解读文本。
 */
public class AiAnalysisVO {

    /** 被分析的单据类型, 如 SAL_DELIVERY. */
    private String bizType;

    /** 被分析单据 id. */
    private Long bizId;

    /** 实际使用的模型名 (未配置时为 "disabled"). */
    private String model;

    /** 模型解读文本. */
    private String content;

    /** v1.1.77 新增: 实际命中的 provider name (供运维日志/前端可选展示). */
    private String llmProvider;

    public AiAnalysisVO() {}

    public AiAnalysisVO(String bizType, Long bizId, String model, String content) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.model = model;
        this.content = content;
    }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }
}
