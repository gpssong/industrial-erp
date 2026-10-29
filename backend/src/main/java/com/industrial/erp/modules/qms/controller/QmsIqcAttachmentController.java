package com.industrial.erp.modules.qms.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.qms.entity.QmsIqcAttachment;
import com.industrial.erp.modules.qms.service.QmsIqcAttachmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v1.1.101 XK — IQC 附件/照片接口.
 *
 * <p>App 端两步: ① POST /system/upload/file 拿 url → ② POST /qms/iqc/{id}/attachment/bind 登记到 IQC.
 * 触发器 trg_qms_iqc_attachment_protect 拦截已审核单据附件删除.
 */
@Tag(name = "IQC 附件")
@RestController
@RequestMapping("/qms/iqc")
public class QmsIqcAttachmentController {

    private final QmsIqcAttachmentService attachService;

    public QmsIqcAttachmentController(QmsIqcAttachmentService attachService) {
        this.attachService = attachService;
    }

    // v1.1.135: App 成品检/来料检的现场照片走下面 list + bind 两个接口, 但 App 权限树只暴露 quality:inspection:*,
    // 只认 qms:iqc:* 会让非超管检验员在手机上 403 (items 接口早已 OR 放宽, 附件漏了)。
    // verify (PC 质检员复核) 与 all-passed App 不调, 有意不放宽。
    @SaCheckPermission(value = {"qms:iqc:list", "qms:iqc:detail",
            "quality:inspection:list", "quality:inspection:check"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/{iqcId}/attachments")
    public R<List<QmsIqcAttachment>> list(@PathVariable Long iqcId) {
        return R.ok(attachService.listByIqcId(iqcId));
    }

    @SaCheckPermission(value = {"qms:iqc:detail", "quality:inspection:check"},
            mode = SaMode.OR, orRole = "admin")
    @PostMapping("/{iqcId}/attachment/bind")
    public R<Map<String, Object>> bind(@PathVariable Long iqcId,
                                       @RequestBody Map<String, Object> body) {
        String fileUrl = (String) body.get("fileUrl");
        String fileName = (String) body.get("fileName");
        Integer fileSizeKb = body.get("fileSizeKb") == null ? null
                : (body.get("fileSizeKb") instanceof Number ? ((Number) body.get("fileSizeKb")).intValue()
                : Integer.parseInt(body.get("fileSizeKb").toString()));
        String attachType = (String) body.getOrDefault("attachType", "OTHER");
        Long id = attachService.bind(iqcId, fileUrl, fileName, fileSizeKb, attachType);
        return R.ok(Map.of("id", id));
    }

    @SaCheckPermission(value = {"qms:iqc:detail"}, orRole = "admin")
    @PostMapping("/attachment/{id}/verify")
    public R<Void> verify(@PathVariable Long id) {
        attachService.markVerified(id);
        return R.ok();
    }
}
