package com.industrial.erp.modules.production.controller;

import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.service.PrdFinishedInService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "成品入库")
@RestController
@RequestMapping("/production/finished-in")
public class PrdFinishedInController {

    public PrdFinishedInController(PrdFinishedInService service) {
        this.service = service;
    }
    private final PrdFinishedInService service;

    @GetMapping("/page")
    public R<PageResult<PrdFinishedIn>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "20") Integer pageSize,
                                              @RequestParam(required = false) String billNo) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo)));
    }

    @PostMapping
    public R<Void> add(@RequestBody PrdFinishedIn in) { service.add(in); return R.ok(); }

    @PostMapping("/{id}/check")
    public R<Void> check(@PathVariable Long id) { service.check(id); return R.ok(); }
}
