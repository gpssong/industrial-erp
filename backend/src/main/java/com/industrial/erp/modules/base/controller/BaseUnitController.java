package com.industrial.erp.modules.base.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseUnit;
import com.industrial.erp.modules.base.mapper.BaseUnitMapper;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "计量单位")
@RestController
@RequestMapping("/base/unit")
public class BaseUnitController {

    public BaseUnitController(BaseUnitMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }
    private final BaseUnitMapper mapper;
    private final PermissionService permService;

    @GetMapping("/list")
    public R<List<BaseUnit>> list() { permService.requirePerm("base:unit:list"); return R.ok(mapper.selectList(null)); }
    @PostMapping
    public R<Void> add(@RequestBody BaseUnit u) { permService.requirePerm("base:unit:add"); if (u.getStatus()==null) u.setStatus(1); mapper.insert(u); return R.ok(); }
    @PutMapping
    public R<Void> update(@RequestBody BaseUnit u) { permService.requirePerm("base:unit:edit"); mapper.updateById(u); return R.ok(); }
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { permService.requirePerm("base:unit:delete"); mapper.deleteById(id); return R.ok(); }
}
