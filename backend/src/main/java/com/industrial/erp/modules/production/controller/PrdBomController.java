package com.industrial.erp.modules.production.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.service.PrdBomService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "BOM管理")
@RestController
@RequestMapping("/production/bom")
public class PrdBomController {

    public PrdBomController(PrdBomService service) {
        this.service = service;
    }
    private final PrdBomService service;

    @GetMapping("/page")
    public R<PageResult<PrdBom>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "20") Integer pageSize,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, keyword)));
    }

    @GetMapping("/{id}")
    public R<PrdBom> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody PrdBom bom) { service.add(bom); return R.ok(); }

    @PutMapping
    public R<Void> update(@RequestBody PrdBom bom) { service.update(bom); return R.ok(); }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
