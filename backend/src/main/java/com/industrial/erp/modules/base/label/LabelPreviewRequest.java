package com.industrial.erp.modules.base.label;

import java.util.List;

/**
 * POST /base/label/preview 的请求体（普通 bean，无 Lombok，与本项目一致）。
 *
 * <p>templateId 为空时由 Controller 取该 bizType 的默认模板（A8 的 defaultOf）——
 * 模板解析归 Controller，{@link LabelPreviewService} 只接已解析好的模板 (A9 裁定 R1)。
 * lineIds 只在原料垛标 / 箱标按行出标时有值；成品卷标没有行概念，留空。
 */
public class LabelPreviewRequest {
    private String bizType;
    private Long templateId;
    private Long bizId;
    private List<Long> lineIds;

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public List<Long> getLineIds() { return lineIds; }
    public void setLineIds(List<Long> lineIds) { this.lineIds = lineIds; }
}
