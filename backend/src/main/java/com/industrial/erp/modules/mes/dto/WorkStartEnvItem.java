package com.industrial.erp.modules.mes.dto;

/**
 * v1.1.151 — 开工前生产环境检查单项判定结果.
 *
 * <p>code 对应 {@code XkEnvCheckTemplate} 的检查项编码; pass 非 true 一律视为不符合,
 * 开工路径会拒绝并提示先整改。note 在判不符合时记录现场问题。
 */
public class WorkStartEnvItem {
    private String code;
    private Boolean pass;
    private String note;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Boolean getPass() { return pass; }
    public void setPass(Boolean pass) { this.pass = pass; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
