package com.industrial.erp.modules.pdm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.pdm.entity.MesProcessParam;
import com.industrial.erp.modules.pdm.service.PdmProcessParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v1.1.90 PDM — 工艺参数 CRUD 接口.
 *
 * <p>一个工序 (mes_process_def) 可配多条参数 (温度/压力/速度/时间 + 上下限).
 * 列表按工序 id 拉, 新增/更新 一行=一条参数, 删除走 id.
 */
@Tag(name = "工艺参数")
@RestController
@RequestMapping("/pdm/process/param")
public class PdmProcessParamController {

    private final PdmProcessParamService svc;

    public PdmProcessParamController(PdmProcessParamService svc) {
        this.svc = svc;
    }

    @Operation(summary = "某工序的全部参数 (按 sort_no 升序)")
    @SaCheckPermission(value = {"pdm:param:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<MesProcessParam>> list(@RequestParam Long processDefId) {
        return R.ok(svc.list(processDefId));
    }

    @Operation(summary = "新增/更新工艺参数")
    @SaCheckPermission(value = {"pdm:param:add", "pdm:param:edit"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping
    public R<Void> save(@RequestBody MesProcessParam p) {
        svc.save(p);
        return R.ok();
    }

    @Operation(summary = "删除工艺参数 (软删)")
    @SaCheckPermission(value = {"pdm:param:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
