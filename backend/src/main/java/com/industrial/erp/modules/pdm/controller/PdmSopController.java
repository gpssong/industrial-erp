package com.industrial.erp.modules.pdm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.pdm.entity.MesProcessSop;
import com.industrial.erp.modules.pdm.service.PdmSopService;
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
 * v1.1.90 PDM — 作业指导书 (SOP) 接口.
 *
 * <p>端点:
 * <ul>
 *   <li>GET  /list?processDefId= — 该工序全部 SOP (按 effective_date DESC)</li>
 *   <li>GET  /active?processDefId= — 该工序当前 ACTIVE 的 SOP</li>
 *   <li>POST / — 新增/更新 (草稿)</li>
 *   <li>POST /{id}/activate — 激活 (同工序其他 DRAFT/ACTIVE 标 DEPRECATED)</li>
 *   <li>DELETE /{id} — 删除 (仅 DRAFT 可删)</li>
 * </ul>
 */
@Tag(name = "作业指导书")
@RestController
@RequestMapping("/pdm/process/sop")
public class PdmSopController {

    private final PdmSopService svc;

    public PdmSopController(PdmSopService svc) {
        this.svc = svc;
    }

    @Operation(summary = "某工序全部 SOP (按生效日期倒序)")
    @SaCheckPermission(value = {"pdm:sop:list"}, orRole = "admin")
    @GetMapping("/list")
    public R<List<MesProcessSop>> list(@RequestParam Long processDefId) {
        return R.ok(svc.list(processDefId));
    }

    @Operation(summary = "某工序当前生效的 SOP (ACTIVE, 同时刻唯一)")
    @SaCheckPermission(value = {"pdm:sop:list"}, orRole = "admin")
    @GetMapping("/active")
    public R<MesProcessSop> active(@RequestParam Long processDefId) {
        return R.ok(svc.getActive(processDefId));
    }

    @Operation(summary = "新增/更新 SOP (草稿)")
    @SaCheckPermission(value = {"pdm:sop:add", "pdm:sop:edit"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping
    public R<Void> save(@RequestBody MesProcessSop sop) {
        svc.save(sop);
        return R.ok();
    }

    @Operation(summary = "激活 SOP (同工序其他非废弃条目沉余为 DEPRECATED)")
    @SaCheckPermission(value = {"pdm:sop:edit"}, orRole = "admin")
    @PostMapping("/{id}/activate")
    public R<Void> activate(@PathVariable Long id) {
        svc.activate(id);
        return R.ok();
    }

    @Operation(summary = "删除 SOP (仅 DRAFT 可删)")
    @SaCheckPermission(value = {"pdm:sop:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok();
    }
}
