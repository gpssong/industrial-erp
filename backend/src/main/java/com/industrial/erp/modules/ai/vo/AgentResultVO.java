package com.industrial.erp.modules.ai.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.75 任务2 — agent 运行结果.
 *
 * <p>{@code proposedActions} 是模型提议的<b>写操作</b> (JSON 字符串列表), 后端<b>不执行</b>,
 * 前端弹确认框, 用户点确认后自行调对应写端点。只读查询已在后端完成并融入 {@code answer}。
 */
public class AgentResultVO {

    private String answer;
    private List<String> proposedActions = new ArrayList<>();
    private int stepsUsed;
    private String model;

    public AgentResultVO() {}

    public AgentResultVO(String answer, List<String> proposedActions, int stepsUsed, String model) {
        this.answer = answer;
        this.proposedActions = proposedActions;
        this.stepsUsed = stepsUsed;
        this.model = model;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public List<String> getProposedActions() { return proposedActions; }
    public void setProposedActions(List<String> proposedActions) { this.proposedActions = proposedActions; }
    public int getStepsUsed() { return stepsUsed; }
    public void setStepsUsed(int stepsUsed) { this.stepsUsed = stepsUsed; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
