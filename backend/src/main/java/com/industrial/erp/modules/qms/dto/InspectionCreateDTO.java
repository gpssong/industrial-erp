package com.industrial.erp.modules.qms.dto;

import com.industrial.erp.modules.qms.entity.MesQualityDefect;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;

import java.util.List;

/**
 * v1.1.90 QMS — 检验单创建 DTO (head + defects, 一次性提交).
 *
 * <p>由前端编辑器一次性 POST, 服务端拆分头行分别 insert.
 * PC 端编辑器走此 DTO; App 端瘦客户端可能只传 head (无 defects 列表), service 层做空安全.
 */
public class InspectionCreateDTO {
    private MesQualityInspection head;
    private List<MesQualityDefect> defects;

    public MesQualityInspection getHead() { return head; }
    public void setHead(MesQualityInspection head) { this.head = head; }
    public List<MesQualityDefect> getDefects() { return defects; }
    public void setDefects(List<MesQualityDefect> defects) { this.defects = defects; }
}
