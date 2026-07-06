package com.industrial.erp.modules.base.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseUnit;
import com.industrial.erp.modules.base.mapper.BaseUnitMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "计量单位")
@RestController
@RequestMapping("/base/unit")
public class BaseUnitController {

    public BaseUnitController(BaseUnitMapper mapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.mapper = mapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }
    private final BaseUnitMapper mapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    @GetMapping("/list")
    public R<List<BaseUnit>> list() { permService.requirePerm("base:unit:list"); return R.ok(mapper.selectList(null)); }
    @PostMapping
    public R<Void> add(@RequestBody BaseUnit u) { permService.requirePerm("base:unit:add"); if (u.getStatus()==null) u.setStatus(1); mapper.insert(u); return R.ok(); }
    @PutMapping
    public R<Void> update(@RequestBody BaseUnit u) { permService.requirePerm("base:unit:edit"); mapper.updateById(u); return R.ok(); }
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        permService.requirePerm("base:unit:delete");
        BaseUnit u = mapper.selectById(id);
        if (u == null) throw BizException.of("计量单位不存在或已删除");
        mapper.update(null, new LambdaUpdateWrapper<BaseUnit>().eq(BaseUnit::getId, id).set(BaseUnit::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("计量单位", String.valueOf(id), u, null);
        return R.ok();
    }
}
