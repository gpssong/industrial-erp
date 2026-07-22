package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysBackupRecord;
import com.industrial.erp.modules.system.service.BackupService;
import com.industrial.erp.modules.system.service.SysBackupRecordService;
import com.industrial.erp.security.PermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "数据备份")
@RestController
@RequestMapping("/system/backup")
public class SysBackupController {

    public SysBackupController(BackupService backupService, SysBackupRecordService recordService, PermissionService permService) {
        this.backupService = backupService;
        this.recordService = recordService;
        this.permService = permService;
    }

    private final BackupService backupService;
    private final SysBackupRecordService recordService;
    private final PermissionService permService;

    @GetMapping("/page")
    @SaCheckLogin
    public R<PageResult<SysBackupRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "20") Integer pageSize) {
        IPage<SysBackupRecord> p = recordService.page(new Page<>(pageNum, pageSize));
        return R.ok(PageResult.of(p));
    }

    /**
     * 执行备份（仅限超级管理员）.
     */
    @PostMapping("/manual")
    @SaCheckLogin
    @SaCheckRole("admin")
    public R<String> manualBackup() {
        permService.requireSuperAdmin();
        String name = backupService.backup(2);
        return R.ok(name);
    }

    /**
     * 恢复备份数据（仅限超级管理员）.
     */
    @PostMapping("/restore/{id}")
    @SaCheckLogin
    @SaCheckRole("admin")
    public R<Void> restore(@PathVariable Long id) {
        permService.requireSuperAdmin();
        SysBackupRecord r = recordService.getById(id);
        if (r == null) return R.fail("备份记录不存在");
        backupService.restore(r.getFilePath());
        return R.ok();
    }

    /**
     * 删除备份文件（仅限超级管理员）.
     */
    @DeleteMapping("/{id}")
    @SaCheckLogin
    @SaCheckRole("admin")
    public R<Void> delete(@PathVariable Long id) {
        permService.requireSuperAdmin();
        SysBackupRecord r = recordService.getById(id);
        if (r != null) {
            backupService.deleteFile(r.getFilePath());
            recordService.removeById(id);
        }
        return R.ok();
    }

    /**
     * 恢复出厂设置（仅限超级管理员）.
     */
    @PostMapping("/factory-reset")
    @SaCheckLogin
    @SaCheckRole("admin")
    public R<Void> factoryReset() {
        permService.requireSuperAdmin();
        backupService.factoryReset();
        return R.ok();
    }

    /**
     * 清空指定表数据（仅限超级管理员）.
     */
    @PostMapping("/clear")
    @SaCheckLogin
    @SaCheckRole("admin")
    public R<Void> clearData(@RequestBody java.util.List<String> tables) {
        permService.requireSuperAdmin();
        backupService.clearData(tables);
        return R.ok();
    }
}
