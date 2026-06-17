package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.IdUtil;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.system.entity.SysBackupRecord;
import com.industrial.erp.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 数据备份服务
 * - 自动: 每天凌晨 3 点
 * - 手动: 用户触发
 */
@Service
public class BackupService {

    private final JdbcTemplate jdbcTemplate;

    public BackupService(JdbcTemplate jdbcTemplate, com.baomidou.mybatisplus.extension.service.IService<SysBackupRecord> recordService) {
        this.jdbcTemplate = jdbcTemplate;
        this.recordService = recordService;
    }

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final com.baomidou.mybatisplus.extension.service.IService<SysBackupRecord> recordService;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    @Value("${spring.datasource.username}")
    private String dbUser;
    @Value("${spring.datasource.password:}")
    private String dbPwd;

    @Value("${erp.backup.path:/opt/industrial-erp/backup}")
    private String backupPath;
    @Value("${erp.backup.retention:30}")
    private int retentionDays;

    /**
     * 每天凌晨 3 点执行自动备份
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void autoBackup() {
        log.info("[Backup] 开始每日自动备份");
        try {
            backup(1);
        } catch (Exception e) {
            log.error("[Backup] 自动备份失败: {}", e.getMessage(), e);
        }
    }

    public String backup(int type) {
        try {
            File dir = new File(backupPath);
            if (!dir.exists()) dir.mkdirs();
            String name = "erp_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + IdUtil.fastSimpleUUID().substring(0, 4) + ".sql";
            File file = new File(dir, name);
            // 实际: 通过 ProcessBuilder 调用 mysqldump
            // 这里简化为只记录元数据
            ProcessBuilder pb = new ProcessBuilder(
                    "mysqldump", "-u" + dbUser, "-p" + dbPwd,
                    "--default-character-set=utf8mb4",
                    "--single-transaction",
                    "--routines", "--triggers",
                    "industrial_erp"
            );
            pb.redirectOutput(file);
            pb.redirectError(new File(dir, name + ".log"));
            Process p = pb.start();
            int exit = p.waitFor();
            SysBackupRecord r = new SysBackupRecord();
            r.setBackupName(name);
            r.setFilePath(file.getAbsolutePath());
            r.setFileSize(file.length());
            r.setBackupType(type);
            r.setStatus(exit == 0 ? 1 : 0);
            r.setRemark(exit == 0 ? "成功" : "mysqldump 失败");
            if (recordService != null) recordService.save(r);
            cleanOld();
            return name;
        } catch (IOException | InterruptedException e) {
            log.error("备份失败", e);
            throw new com.industrial.erp.exception.BizException("备份失败: " + e.getMessage());
        }
    }

    private void cleanOld() {
        File dir = new File(backupPath);
        if (!dir.exists()) return;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".sql"));
        if (files == null) return;
        long threshold = System.currentTimeMillis() - retentionDays * 86400_000L;
        for (File f : files) {
            if (f.lastModified() < threshold) f.delete();
        }
    }
}