package com.industrial.erp.modules.outsource.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.outsource.entity.OutIssue;
import com.industrial.erp.modules.outsource.entity.OutProcessingIn;
import com.industrial.erp.modules.outsource.service.OutsourceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "委外加工")
@RestController
@RequestMapping("/outsource")
public class OutsourceController {

    public OutsourceController(OutsourceService service) {
        this.service = service;
    }
    private final OutsourceService service;

    @PostMapping("/issue")
    public R<Void> addIssue(@RequestBody OutIssue i) { service.addIssue(i); return R.ok(); }

    @PostMapping("/issue/{id}/check")
    public R<Void> checkIssue(@PathVariable Long id) { service.checkIssue(id); return R.ok(); }

    @PostMapping("/pi")
    public R<Void> addPi(@RequestBody OutProcessingIn i) { service.addProcessingIn(i); return R.ok(); }

    @PostMapping("/pi/{id}/check")
    public R<Void> checkPi(@PathVariable Long id) { service.checkProcessingIn(id); return R.ok(); }
}
