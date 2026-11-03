package com.industrial.erp.modules.purchase.bill;

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

/**
 * v1.1.158 XK — 检验报告号自动生成 (BG-YL-YYYYMMDD-NNN).
 *
 * <p>格式带分隔符 + 3 位流水, 与 {@link com.industrial.erp.utils.BillNoGenerator} 的
 * {@code prefix+yyyyMMdd+%04d} 不同, 故独立实现, 但复用同款可靠性:
 * <ul>
 *   <li>Redis 自增计数 (无 TTL, 淘汰会从 1 重算 → 与当天已发号重复);</li>
 *   <li>发完跟库内当天最大号对账, 计数器落后就抬升重发
 *       (主备复制把别处单号带进来 / 换 Redis 实例都可能让计数器落后)。</li>
 * </ul>
 *
 * <p>对账列: {@code pur_receipt.inspection_report_no} (XK 展示冗余快照, 报告号最终落点)。
 */
@Component
public class ReportNoGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportNoGenerator.class);

    /** 报告号前缀 (BG = 报告, YL = 原料)。 */
    public static final String PREFIX = "BG-YL-";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Redis 计数键命名空间, 与单据号分开。 */
    private static final String REDIS_KEY = "erp:reportno:BG-YL:";

    @Autowired
    private StringRedisTemplate redis;

    /** 对账用; 无数据源时退化纯 Redis。 */
    @Autowired(required = false)
    @Nullable
    private JdbcTemplate jdbc;

    /**
     * 生成检验报告号: {@code BG-YL-} + yyyyMMdd + {@code -} + 3 位当天流水。
     */
    public String generate() {
        String date = LocalDate.now().format(FMT);
        String key = REDIS_KEY + date;
        long seq = incr(key);
        long dbMax = maxSeqInDb(date);
        if (dbMax >= seq) {
            log.warn("[ReportNo] BG-YL 计数器落后: redis={} 库内最大={}, 抬升后重发", seq, dbMax);
            redis.opsForValue().set(key, String.valueOf(dbMax));
            seq = incr(key);
        }
        return PREFIX + date + "-" + String.format("%03d", seq);
    }

    private long incr(String key) {
        Long seq = redis.opsForValue().increment(key);
        if (seq == null) throw BizException.of("检验报告号生成失败");
        return seq;
    }

    /** 库内当天 BG-YL 报告号最大流水; 无数据源 / 查库异常返回 -1 (不阻塞建单)。 */
    private long maxSeqInDb(String date) {
        if (jdbc == null) return -1;
        // 固定前缀, 无外部输入
        String like = PREFIX + date + "-%";
        try {
            String max = jdbc.queryForObject(
                    "SELECT MAX(inspection_report_no) FROM pur_receipt WHERE inspection_report_no LIKE ?",
                    String.class, like);
            if (max == null || max.length() < PREFIX.length() + 8 + 1 + 3) return -1;
            return Long.parseLong(max.substring(max.length() - 3));
        } catch (Exception e) {
            log.warn("[ReportNo] BG-YL 当天最大号对账失败, 按 Redis 计数继续: {}", e.getMessage());
            return -1;
        }
    }
}
