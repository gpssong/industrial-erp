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

    @Value("${erp.backup.path:/Users/tongban/industrial-erp-backup}")
    private String backupPath;
    @Value("${erp.backup.retention:30}")
    private int retentionDays;
    @Value("${erp.backup.sql-path:/Users/tongban/Documents/根据前端开发erp 2/erp-system/sql}")
    private String sqlPath;

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
            ProcessBuilder pb = new ProcessBuilder(
                    "/opt/homebrew/bin/mysqldump", "-u" + dbUser, "-p" + dbPwd,
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

    /** 恢复数据库（从指定SQL文件） */
    public void restore(String filePath) {
        File sqlFile = new File(filePath);
        if (!sqlFile.exists()) {
            throw new com.industrial.erp.exception.BizException("备份文件不存在: " + filePath);
        }
        // 从 jdbcUrl 提取数据库名
        String dbName = "industrial_erp";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/opt/homebrew/bin/mysql",
                    "-u" + dbUser,
                    "-p" + dbPwd,
                    dbName
            );
            pb.redirectInput(sqlFile);
            pb.redirectError(new File(backupPath, "restore_error.log"));
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new com.industrial.erp.exception.BizException("恢复失败，exit=" + exit);
            }
            log.info("[Backup] 数据库恢复成功: {}", filePath);
        } catch (IOException | InterruptedException e) {
            log.error("恢复失败", e);
            throw new com.industrial.erp.exception.BizException("恢复失败: " + e.getMessage());
        }
    }

    /** 删除物理备份文件 */
    public void deleteFile(String filePath) {
        if (filePath == null) return;
        File f = new File(filePath);
        if (f.exists()) f.delete();
    }

    /** 恢复出厂设置：清空数据+重跑SQL脚本 */
    public void factoryReset() {
        String[] scripts = {
            "01_schema_system.sql",
            "02_schema_base.sql",
            "03_schema_purchase.sql",
            "04_schema_sales.sql",
            "05_schema_inventory.sql",
            "06_schema_production.sql",
            "07_schema_outsource_finance.sql",
            "08_schema_misc.sql",
            "09_seed_data.sql"
        };
        String dbName = "industrial_erp";
        for (String script : scripts) {
            File f = new File(sqlPath, script);
            if (!f.exists()) {
                log.warn("[FactoryReset] SQL脚本不存在: {}", f.getAbsolutePath());
                continue;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "/opt/homebrew/bin/mysql",
                        "-u" + dbUser,
                        "-p" + dbPwd,
                        dbName
                );
                pb.redirectInput(f);
                pb.redirectError(new File(backupPath, "factory_reset_error.log"));
                Process p = pb.start();
                int exit = p.waitFor();
                log.info("[FactoryReset] 执行 {} -> exit={}", script, exit);
                if (exit != 0) {
                    throw new com.industrial.erp.exception.BizException("执行 " + script + " 失败，exit=" + exit);
                }
            } catch (IOException | InterruptedException e) {
                log.error("恢复出厂设置失败: {}", script, e);
                throw new com.industrial.erp.exception.BizException("恢复出厂设置失败: " + e.getMessage());
            }
        }
        log.info("[FactoryReset] 恢复出厂设置完成");
    }
}