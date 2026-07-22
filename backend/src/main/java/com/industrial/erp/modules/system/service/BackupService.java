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

    @Value("${erp.backup.path:#{'${user.home}'}/erp-backup}")
    private String backupPath;
    @Value("${erp.backup.retention-days:30}")
    private int retentionDays;
    @Value("${erp.backup.sql-path:./sql}")
    private String sqlPath;
    @Value("${erp.backup.mysqldump-path:mysqldump}")
    private String mysqldumpPath;
    @Value("${erp.backup.mysql-path:mysql}")
    private String mysqlPath;
    @Value("${erp.backup.db-host:127.0.0.1}")
    private String dbHost;
    @Value("${erp.backup.db-port:3306}")
    private int dbPort;

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
            // 安全加固 (P1-4): 不再用 "-u<pwd>" 拼接命令行 (mysql client 会把含特殊字符的 pwd 误解析),
            // 改用 --defaults-extra-file 写入临时文件, 进程结束后立即删除.
            File defaultsFile = writeMysqlDefaultsFile();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        mysqldumpPath,
                        "--defaults-extra-file=" + defaultsFile.getAbsolutePath(),
                        "-h" + dbHost, "-P" + dbPort,
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
            } finally {
                // 临时文件无论成功失败都立即删除, 避免密码明文残留
                if (defaultsFile.exists()) defaultsFile.delete();
            }
        } catch (IOException | InterruptedException e) {
            log.error("备份失败", e);
            throw new com.industrial.erp.exception.BizException("备份失败: " + e.getMessage());
        }
    }

    /**
     * 写一个 MySQL client 用的 defaults file (含 user/password).
     * 临时文件 chown 0600, 进程结束后立即删除 — 避免密码以明文形式出现在命令行/进程列表中.
     */
    private File writeMysqlDefaultsFile() throws IOException {
        File f = File.createTempFile("erp-mysql-", ".cnf");
        f.setReadable(false, false);  // 0600
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        String content = "[client]\nuser=" + dbUser + "\npassword=\"" + dbPwd.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n";
        java.nio.file.Files.writeString(f.toPath(), content,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        return f;
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
        File defaultsFile = null;
        try {
            defaultsFile = writeMysqlDefaultsFile();
            ProcessBuilder pb = new ProcessBuilder(
                    mysqlPath,
                    "--defaults-extra-file=" + defaultsFile.getAbsolutePath(),
                    "-h" + dbHost, "-P" + dbPort,
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
        } finally {
            if (defaultsFile != null && defaultsFile.exists()) defaultsFile.delete();
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
        File defaultsFile = null;
        try {
            defaultsFile = writeMysqlDefaultsFile();
            for (String script : scripts) {
                File f = new File(sqlPath, script);
                if (!f.exists()) {
                    log.warn("[FactoryReset] SQL脚本不存在: {}", f.getAbsolutePath());
                    continue;
                }
                ProcessBuilder pb = new ProcessBuilder(
                        mysqlPath,
                        "--defaults-extra-file=" + defaultsFile.getAbsolutePath(),
                        "-h" + dbHost, "-P" + dbPort,
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
            }
            log.info("[FactoryReset] 恢复出厂设置完成");
        } catch (IOException | InterruptedException e) {
            log.error("恢复出厂设置失败", e);
            throw new com.industrial.erp.exception.BizException("恢复出厂设置失败: " + e.getMessage());
        } finally {
            if (defaultsFile != null && defaultsFile.exists()) defaultsFile.delete();
        }
    }

    /** 选择性清空指定表数据（保留系统表） */
    public void clearData(java.util.List<String> tables) {
        if (tables == null || tables.isEmpty()) return;
        // 安全白名单：只允许清空以下业务表
        java.util.Set<String> allowed = java.util.Set.of(
            // 基础数据
            "base_product", "base_product_category", "base_product_unit", "base_price_level",
            "base_customer", "base_supplier", "base_unit",
            "base_warehouse", "base_warehouse_area", "base_warehouse_location",
            // 采购
            "pur_inquiry", "pur_inquiry_detail",
            "pur_order", "pur_order_detail",
            "pur_receipt", "pur_receipt_detail",
            "pur_return", "pur_return_detail",
            "pur_payment",
            // 销售
            "sal_quotation", "sal_quotation_detail",
            "sal_order", "sal_order_detail",
            "sal_delivery", "sal_delivery_detail",
            "sal_return", "sal_return_detail",
            "sal_receipt",
            // 库存
            "inv_stock", "inv_ledger", "inv_warning", "inv_transfer", "inv_transfer_detail",
            "inv_cut_process", "inv_cut_process_detail",
            "inv_check", "inv_check_detail",
            "inv_profit_loss", "inv_profit_loss_detail",
            "inv_serial_no",
            // 生产
            "prd_bom", "prd_bom_detail",
            "prd_order", "prd_finished_in",
            "prd_requisition", "prd_requisition_detail",
            "prd_process",
            // 外协
            "out_issue", "out_issue_detail",
            "out_processing_in", "out_processing_in_detail",
            "out_process_fee",
            // 财务
            "fin_arap", "fin_cash_flow", "fin_cash_writeoff",
            "fin_partner_balance", "fin_reconciliation",
            // 报表
            "rpt_daily_snapshot",
            // 单据附件
            "sys_bill_attachment"
        );
        for (String table : tables) {
            if (!allowed.contains(table)) {
                throw new com.industrial.erp.exception.BizException("不允许清空系统表: " + table);
            }
        }
        for (String table : tables) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
                log.info("[ClearData] 已清空表: {}", table);
            } catch (Exception e) {
                log.error("[ClearData] 清空表失败: {}", table, e);
                throw new com.industrial.erp.exception.BizException("清空表 " + table + " 失败: " + e.getMessage());
            }
        }
    }
}