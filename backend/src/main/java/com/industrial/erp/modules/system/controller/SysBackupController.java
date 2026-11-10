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

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;

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
    public R<Void> clearData(@RequestBody java.util.List<String> tables) {
        permService.requireSuperAdmin();
        backupService.clearData(tables);
        return R.ok();
    }

    /**
     * 下载备份文件（仅限超级管理员）.
     * 直接写 HttpServletResponse 输出流 (本站 WebMvcConfig 清空了默认转换器, 只留 String+Jackson,
     * 返回 ResponseEntity<Resource> 会 "No converter for FileSystemResource"; 与 CSV/PDF 导出同套路).
     */
    @GetMapping("/download/{id}")
    @SaCheckLogin
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        permService.requireSuperAdmin();
        SysBackupRecord r = recordService.getById(id);
        if (r == null) throw new com.industrial.erp.exception.BizException("备份记录不存在");
        File f = backupService.resolveBackupFile(r.getFilePath());
        String name = r.getBackupName() == null ? f.getName() : r.getBackupName();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/octet-stream");
        response.setContentLengthLong(f.length());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        try (OutputStream out = response.getOutputStream()) {
            Files.copy(f.toPath(), out);
            out.flush();
        }
    }

    /**
     * 按时间范围删除备份（仅限超级管理员）: 删除 createTime 落在 [startTime, endTime] 的记录与物理文件.
     */
    @PostMapping("/delete-by-time")
    @SaCheckLogin
    public R<Integer> deleteByTime(@RequestBody DeleteByTimeReq req) {
        permService.requireSuperAdmin();
        if (req == null || req.getStartTime() == null || req.getEndTime() == null) return R.fail("请选择起止时间");
        if (req.getStartTime().isAfter(req.getEndTime())) return R.fail("开始时间不能晚于结束时间");
        java.util.List<SysBackupRecord> list = recordService.lambdaQuery()
                .ge(SysBackupRecord::getCreateTime, req.getStartTime())
                .le(SysBackupRecord::getCreateTime, req.getEndTime())
                .list();
        int n = 0;
        for (SysBackupRecord r : list) {
            backupService.deleteFile(r.getFilePath());
            recordService.removeById(r.getId());
            n++;
        }
        return R.ok(n);
    }

    public static class DeleteByTimeReq {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    }
}
