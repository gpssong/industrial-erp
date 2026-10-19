package com.industrial.erp.modules.qms.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import com.industrial.erp.modules.qms.service.QmsIqcItemService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v1.1.101 XK — IQC 检验项明细接口.
 *
 * <p>App 端 "保存检验记录" / "生成报告并自动入库" 两条路径都先 batchSave 全部检验项,
 * 再触发 QmsInspectionService.check (后者校验 pass_flag=1 才放行).
 */
@Tag(name = "IQC 检验项")
@RestController
@RequestMapping("/qms/iqc")
public class QmsIqcItemController {

    private final QmsIqcItemService itemService;

    public QmsIqcItemController(QmsIqcItemService itemService) {
        this.itemService = itemService;
    }

    @SaCheckPermission(value = {"qms:iqc:list", "qms:iqc:detail"}, orRole = "admin")
    @GetMapping("/{iqcId}/items")
    public R<List<QmsIqcItem>> list(@PathVariable Long iqcId) {
        return R.ok(itemService.listByIqcId(iqcId));
    }

    @SaCheckPermission(value = {"qms:iqc:detail"}, orRole = "admin")
    @PutMapping("/{iqcId}/items")
    public R<Void> batchSave(@PathVariable Long iqcId, @RequestBody List<QmsIqcItem> items) {
        itemService.batchSave(iqcId, items);
        return R.ok();
    }

    @SaCheckPermission(value = {"qms:iqc:detail"}, orRole = "admin")
    @GetMapping("/{iqcId}/all-passed")
    public R<Map<String, Object>> allPassed(@PathVariable Long iqcId) {
        return R.ok(Map.of("allPassed", itemService.allPassed(iqcId)));
    }
}
