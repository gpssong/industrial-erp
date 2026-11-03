package com.industrial.erp.modules.mes.dto;

import java.util.List;

/**
 * v1.1.132 — App 现场开工确认入参 (吹膜 / 制袋共用).
 *
 * <p>前端 Long 主键一律以字符串透传 (R21 雪花 id 精度丢失教训), Jackson 会自动转 Long.
 */
public class WorkStartConfirmDTO {
    /** BAG 开工时指定工序实例 (prd_process.id); BLOW 可空 */
    private Long processId;
    /** BLOW / BAG — 仅 v1.1.151 环境不合格登记接口使用 */
    private String stage;
    /** 生产环境是否合格 — 必须显式 true, 否则拒绝开工 */
    private Boolean envQualified;
    /** 逐项环境检查结果 (v1.1.151, GB 31603 清单, 11 项必答且全部合格才可开工) */
    private List<WorkStartEnvItem> envItems;
    /** 吹膜投料原料批号 — 食品级商品必填 (至少 1 个); 工业品选填 (v1.1.141) */
    private List<String> rawBatchNos;
    /** 原料批号照片 URL — 食品级商品必填 (至少 1 张); 工业品选填 (v1.1.141) */
    private List<String> photoUrls;
    /**
     * v1.1.160 按 BOM 行选定的「出库仓库 + 库存批号」(只读 productId/warehouseId/batchNo).
     * 落 prd_order_material 后 release() 生成的领料单才带得上批号 —— 领料审核不再卡在「库存不存在」.
     */
    private List<com.industrial.erp.modules.production.entity.PrdOrderMaterial> materials;
    private String remark;

    public Long getProcessId() { return processId; }
    public void setProcessId(Long processId) { this.processId = processId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Boolean getEnvQualified() { return envQualified; }
    public void setEnvQualified(Boolean envQualified) { this.envQualified = envQualified; }
    public List<WorkStartEnvItem> getEnvItems() { return envItems; }
    public void setEnvItems(List<WorkStartEnvItem> envItems) { this.envItems = envItems; }
    public List<String> getRawBatchNos() { return rawBatchNos; }
    public void setRawBatchNos(List<String> rawBatchNos) { this.rawBatchNos = rawBatchNos; }
    public List<String> getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(List<String> photoUrls) { this.photoUrls = photoUrls; }
    public List<com.industrial.erp.modules.production.entity.PrdOrderMaterial> getMaterials() { return materials; }
    public void setMaterials(List<com.industrial.erp.modules.production.entity.PrdOrderMaterial> materials) { this.materials = materials; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
