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
        PageResult<PrdBom> page = PageResult.of(service.page(pageNum, pageSize, keyword));
        // 配方迁移: 给每条 BOM 注上 "被 N 个成品引用", 给前端 BOM 列表用
        if (page != null && page.getRecords() != null && !page.getRecords().isEmpty()) {
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (PrdBom b : page.getRecords()) ids.add(b.getId());
            java.util.Map<Long, Long> usage = service.countProductsByBomIds(ids);
            for (PrdBom b : page.getRecords()) {
                b.setProductCount(usage.getOrDefault(b.getId(), 0L));
            }
        }
        return R.ok(page);
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
