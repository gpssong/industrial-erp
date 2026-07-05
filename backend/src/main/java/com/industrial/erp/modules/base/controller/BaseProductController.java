package com.industrial.erp.modules.base.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.service.BaseProductService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "商品管理")
@RestController
@RequestMapping("/base/product")
public class BaseProductController {

    public BaseProductController(BaseProductService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    private final BaseProductService service;
    private final ObjectMapper objectMapper;

    @GetMapping("/page")
    public R<PageResult<BaseProduct>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                           @RequestParam(defaultValue = "20") Integer pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Long categoryId) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, keyword, categoryId)));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Void> add(@RequestBody Map<String, Object> body) {
        BaseProduct p = objectMapper.convertValue(body.get("product"), BaseProduct.class);
        List<BaseProductUnit> units = objectMapper.convertValue(body.get("units"),
                new com.fasterxml.jackson.core.type.TypeReference<List<BaseProductUnit>>() {});
        service.add(p, units);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody Map<String, Object> body) {
        BaseProduct p = objectMapper.convertValue(body.get("product"), BaseProduct.class);
        List<BaseProductUnit> units = objectMapper.convertValue(body.get("units"),
                new com.fasterxml.jackson.core.type.TypeReference<List<BaseProductUnit>>() {});
        service.update(p, units);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    @GetMapping("/convert")
    public R<BigDecimal> convert(@RequestParam Long productId, @RequestParam Long unitId, @RequestParam BigDecimal qty) {
        return R.ok(service.convertToMain(productId, unitId, qty));
    }

    /** App 端专用商品搜索 (不检查 base:product:list 权限) */
    @GetMapping("/app-search")
    public R<PageResult<BaseProduct>> appSearch(@RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "20") Integer pageSize,
                                                @RequestParam(required = false) String keyword) {
        // App 端商品搜索: 只要登录就能查 (不需要 base:product:list)
        return R.ok(PageResult.of(service.pageWithoutPerm(pageNum, pageSize, keyword)));
    }
}
