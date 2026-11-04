package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.modules.base.entity.BaseLabelPrintLog;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.mapper.BaseLabelPrintLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 标签打印日志 (docs/32 §2.3 §5)。
 *
 * <p>**失败也要落**：XK 检查时「打错过什么、谁打的、什么时候」只能靠这张表自证。
 * 审计列 create_by/create_time 由 MetaObjectHandler 从当前登录用户回填，别手工 set。
 */
@Service
public class LabelPrintService {

    private static final Logger log = LoggerFactory.getLogger(LabelPrintService.class);

    public LabelPrintService(BaseLabelPrintLogMapper logMapper) { this.logMapper = logMapper; }
    private final BaseLabelPrintLogMapper logMapper;

    public void logSuccess(String bizType, Long bizId, String bizNo, BaseLabelTemplate tpl,
                           String channel, String printerName, String printerMac,
                           int copies, String content) {
        insert(bizType, bizId, bizNo, tpl, channel, printerName, printerMac, copies, content, "SUCCESS", null);
    }

    public void logFail(String bizType, Long bizId, String bizNo, BaseLabelTemplate tpl,
                        String channel, String printerName, String printerMac,
                        int copies, String content, String errorMsg) {
        insert(bizType, bizId, bizNo, tpl, channel, printerName, printerMac, copies, content, "FAIL", errorMsg);
    }

    public List<BaseLabelPrintLog> recent(String bizType, Long bizId, int limit) {
        return logMapper.selectList(new LambdaQueryWrapper<BaseLabelPrintLog>()
                .eq(bizType != null && !bizType.isBlank(), BaseLabelPrintLog::getBizType, bizType)
                .eq(bizId != null, BaseLabelPrintLog::getBizId, bizId)
                .orderByDesc(BaseLabelPrintLog::getId)
                .last("LIMIT " + clampLimit(limit)));
    }

    /** 日志是给人翻的，不是给人搬库的：limit 夹在 1..100，0/负数和 5000 都不许原样拼进 SQL。 */
    static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }

    /** 渲染异常也要能落日志：这里只 catch 落库本身的失败，不把打印异常吞掉。 */
    private void insert(String bizType, Long bizId, String bizNo, BaseLabelTemplate tpl,
                        String channel, String printerName, String printerMac,
                        int copies, String content, String status, String errorMsg) {
        BaseLabelPrintLog row = new BaseLabelPrintLog();
        row.setBizType(bizType);
        row.setBizId(bizId);
        // 这三列里 biz_no/printer_name/printer_mac 收的是**手机上报的字符串**（`/print/log` 是系统边界）。
        // 超宽不截 ⇒ 数据库拒收整行 ⇒ 正好落进下面那个 catch ⇒ App 收到 R.ok、库里一行没有，只剩一条 warn。
        // 这张表的底线是「一行都不丢」，所以按列宽截齐（sql/155:43,47-48），宁可少几个字符。
        row.setBizNo(cut(bizNo, 64));
        if (tpl != null) {
            row.setTemplateId(tpl.getId());
            row.setTemplateCode(tpl.getTemplateCode());
        }
        row.setChannel(channel);
        row.setPrinterName(cut(printerName, 128));
        row.setPrinterMac(cut(printerMac, 32));
        row.setCopies(copies);
        row.setContent(content);
        row.setContentHash(sha256Hex(content));
        row.setStatus(status);
        if (errorMsg != null) row.setErrorMsg(errorMsg.length() > 500 ? errorMsg.substring(0, 500) + "…" : errorMsg);
        // **A10-R2**：落库失败**不许把打印流程带崩**（口径抄 `FeiePrintService.java:186`：`try { save } catch { log.warn }`）。
        // 一次批量打 20 张，第 3 张的日志写不进去不能连累后 17 张；但必须 warn —— 这张表是 XK 检查的自证材料，
        // 丢一行就要能在日志里查到丢了。真正的防线是 `/print/log` 先校验 `bizType`（NOT NULL 列，缺了必落不进）。
        try {
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("标签打印日志落库失败, bizType={}, bizId={}, channel={}, status={}",
                    bizType, bizId, channel, status, e);
        }
    }

    /** 按 DDL 列宽截齐，留一位给省略号 —— 截完的总长正好等于列宽。 */
    private static String cut(String s, int columnWidth) {
        if (s == null || s.length() <= columnWidth) return s;
        return s.substring(0, columnWidth - 1) + "…";
    }

    private static String sha256Hex(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
