package com.industrial.erp.utils;

import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 单据号生成器 (Redis 自增 + 库内最大单号对账).
 */
@Component
public class BillNoGenerator {

    private static final Logger log = LoggerFactory.getLogger(BillNoGenerator.class);

    @Autowired
    private StringRedisTemplate redis;

    /** 对账用; 单测或无数据源场景下可为 null, 此时退化为纯 Redis 自增 (旧行为). */
    @Autowired(required = false)
    @Nullable
    private JdbcTemplate jdbc;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 前缀 → 归属表.列; 用于把 Redis 计数器抬到库内当天最大号之后. */
    private static final Map<String, String> OWNER = new HashMap<>();

    static {
        OWNER.put("PO", "pur_order.bill_no");
        OWNER.put("RKP", "pur_receipt.bill_no");
        OWNER.put("RT", "pur_return.bill_no");
        OWNER.put("SO", "sal_order.bill_no");
        OWNER.put("CKP", "sal_delivery.bill_no");
        OWNER.put("SRT", "sal_return.bill_no");
        OWNER.put("PD", "prd_order.bill_no");
        OWNER.put("RQ", "prd_requisition.bill_no");
        OWNER.put("PFI", "prd_finished_in.bill_no");
        OWNER.put("PSI", "prd_semi_in.bill_no");
        OWNER.put("TR", "inv_transfer.bill_no");
        OWNER.put("CK", "inv_check.bill_no");
        OWNER.put("OI", "out_issue.bill_no");
        OWNER.put("OPI", "out_processing_in.bill_no");
        OWNER.put("INV", "fin_invoice.bill_no");
        OWNER.put("QI", "mes_quality_inspection.bill_no");
        OWNER.put("WM", "wf_maintain_record.record_no");
        OWNER.put("SIN", "wf_safety_record.record_no");
        OWNER.put("EP", "wf_emergency_plan.plan_no");
        // v1.1.147: 补齐 XK/MES/EM/QA/PDM/采购计划/资金流水 的前缀 —
        // 这些前缀此前不在对账表里, Redis 计数器一丢 (换实例 / 被清) 就重复发号,
        // 撞 uniq 唯一键后由调用方 try/catch 吞掉 (打印审计静默不落库就是这条)。
        OWNER.put("XA", "xk_audit_log.log_no");
        OWNER.put("AJ", "xk_safety_check.check_no");
        OWNER.put("EC", "xk_env_check.check_no");
        OWNER.put("WS", "mes_work_start_confirm.confirm_no");
        OWNER.put("MP", "em_maintain_plan.plan_no");
        OWNER.put("MO", "em_maintain_order.order_no");
        OWNER.put("MR", "em_maintain_record.record_no");
        OWNER.put("CR", "qa_calibration_record.record_no");
        OWNER.put("CE", "qa_calibration_equipment.code");
        OWNER.put("BOM", "prd_bom.bom_code");
        OWNER.put("CGJH", "pur_inbound_plan.plan_no");
        OWNER.put("SK", "fin_cash_flow.bill_no");
        OWNER.put("FK", "fin_cash_flow.bill_no");
    }

    /**
     * 生成单据号: prefix + yyyyMMdd + 4位自增。
     *
     * <p>计数器只记本站 Redis 见过的事, 库里的行可能来自别处 (主备复制把主库单号带进备库,
     * 或 Redis 被清空/换实例), 计数器落后就会重复发号, 撞 {@code uniq_*_bill_no} 让建单报
     * 「数据已存在」。所以发完先跟库内当天最大号对账, 落后就抬升计数器重发。
     */
    public String generate(String prefix) {
        String date = LocalDate.now().format(FMT);
        String key = Constants.REDIS_BILL_NO + prefix + ":" + date;
        long seq = incr(key);
        long dbMax = maxSeqInDb(prefix, date);
        if (dbMax >= seq) {
            log.warn("[BillNo] {} 计数器落后: redis={} 库内最大={}, 抬升后重发", prefix, seq, dbMax);
            redis.opsForValue().set(key, String.valueOf(dbMax));
            seq = incr(key);
        }
        // 不设置 TTL: 单号序列一旦被淘汰会从 1 重新计数, 与当天已生成单号重复.
        // Redis 淘汰策略固定为 volatile-lru (只淘汰带 TTL 的缓存), 无 TTL 的序列键不会被回收.
        return prefix + date + String.format("%04d", seq);
    }

    private long incr(String key) {
        Long seq = redis.opsForValue().increment(key);
        if (seq == null) {
            throw BizException.of("单据号生成失败");
        }
        return seq;
    }

    /** 库内当天该前缀的最大序号; 未知前缀 / 无数据源 / 查库异常返回 -1 (不阻塞开单). */
    private long maxSeqInDb(String prefix, String date) {
        if (jdbc == null) return -1;
        String owner = OWNER.get(prefix);
        if (owner == null) return -1;
        int dot = owner.indexOf('.');
        String table = owner.substring(0, dot);
        String column = owner.substring(dot + 1);
        try {
            // table/column 全部来自本类常量, 无外部输入
            String max = jdbc.queryForObject(
                    "SELECT MAX(" + column + ") FROM " + table + " WHERE " + column + " LIKE ?",
                    String.class, prefix + date + "%");
            if (max == null || max.length() < prefix.length() + 12) return -1;
            return Long.parseLong(max.substring(prefix.length() + 8));
        } catch (Exception e) {
            log.warn("[BillNo] {} 当天最大号对账失败, 按 Redis 计数继续: {}", prefix, e.getMessage());
            return -1;
        }
    }
}
