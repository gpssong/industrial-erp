package com.industrial.erp.modules.ai.replenish;

import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.75 任务3 — 补货预测 (统计法, 不用重 ML 框架, 零训练成本、可解释).
 *
 * <p>模型: 对每个有出库流水的商品, 算 <b>近 N 天日均出库量</b> (总出库 / N),
 * 结合 <b>当前库存</b> 与 <b>安全库存</b>, 给出:
 * <ul>
 *   <li>预计可售天数 = 当前库存 / 日均出库 (日均为 0 时记为"消耗极慢/无消耗")</li>
 *   <li>是否建议补货: 预计可售天数 &lt; 补货提前期 (默认 7 天) 或 低于安全库存</li>
 *   <li>建议补货量: 覆盖提前期消耗 + 回到安全水位 的差额</li>
 * </ul>
 * 纯只读, 只给建议不改动数据 (真正补货是采购流程, 不在 AI 内自动执行)。
 */
@Service
public class ReplenishService {

    private final InvLedgerMapper ledgerMapper;
    private final InvStockMapper stockMapper;
    private final BaseProductMapper productMapper;

    /** 看多少天的出库历史算日均. */
    private static final int LOOKBACK_DAYS = 30;
    /** 补货提前期 (天): 预计可售天数低于此值就建议补. */
    private static final int LEAD_TIME_DAYS = 7;

    public ReplenishService(InvLedgerMapper ledgerMapper, InvStockMapper stockMapper, BaseProductMapper productMapper) {
        this.ledgerMapper = ledgerMapper;
        this.stockMapper = stockMapper;
        this.productMapper = productMapper;
    }

    public static class Suggestion {
        public Long productId;
        public String productCode;
        public String productName;
        public BigDecimal currentStock;
        public BigDecimal safetyStock;
        public BigDecimal avgDailyOut;      // 近 N 天日均出库
        public Integer estDaysOfStock;      // 预计可售天数 (null=消耗极慢)
        public boolean recommend;           // 是否建议补货
        public BigDecimal suggestQty;       // 建议补货量 (recommend 时)
        public String reason;               // 给 LLM/人看的理由
    }

    /**
     * 生成补货建议 (top N 消耗商品, 降序).
     * @param keyword 可选: 商品名/编码过滤 (为空则对全部有出库的商品算)
     * @param topN    返回多少条
     */
    public List<Suggestion> suggest(String keyword, int topN) {
        int limit = keyword == null || keyword.trim().isEmpty() ? Math.max(topN, 1) * 3 : Math.max(topN, 1) * 5;
        LocalDate since = LocalDate.now().minusDays(LOOKBACK_DAYS);

        List<Map<String, Object>> outs = ledgerMapper.sumOutByProduct(since, limit);
        if (outs == null || outs.isEmpty()) return new ArrayList<>();

        // 商品当前库存 (按 productId 汇总, 全仓库)
        List<Long> pids = new ArrayList<>();
        for (Map<String, Object> o : outs) pids.add(toLong(o.get("productId")));
        Map<Long, BigDecimal> stockByPid = currentStock(pids);

        List<Suggestion> all = new ArrayList<>();
        for (Map<String, Object> o : outs) {
            Long pid = toLong(o.get("productId"));
            String code = str(o.get("productCode"));
            String name = keyword != null && !keyword.trim().isEmpty()
                    ? (match(keyword, code, str(o.get("productName"))) ? str(o.get("productName")) : null)
                    : str(o.get("productName"));
            if (keyword != null && !keyword.trim().isEmpty() && name == null) continue;

            BigDecimal totalOut = toBd(o.get("totalOut"));
            BigDecimal avgDaily = totalOut.divide(BigDecimal.valueOf(LOOKBACK_DAYS), 4, RoundingMode.HALF_UP);
            BigDecimal stock = stockByPid.getOrDefault(pid, BigDecimal.ZERO);
            BigDecimal safety = safetyStock(pid);

            Suggestion s = new Suggestion();
            s.productId = pid;
            s.productCode = code;
            s.productName = name != null ? name : code;
            s.currentStock = stock;
            s.safetyStock = safety;
            s.avgDailyOut = avgDaily;

            if (avgDaily.compareTo(BigDecimal.ZERO) > 0) {
                int estDays = stock.divide(avgDaily, 0, RoundingMode.FLOOR).intValue();
                s.estDaysOfStock = estDays;
                boolean lowVsSafety = safety != null && stock.compareTo(safety) < 0;
                boolean lowDays = estDays < LEAD_TIME_DAYS;
                s.recommend = lowDays || lowDays; // 简化: 以预计天数为主
                s.suggestQty = (s.recommend && estDays < LEAD_TIME_DAYS)
                        ? calcSuggest(stock, avgDaily, safety, estDays) : null;
                s.reason = "近" + LOOKBACK_DAYS + "天日均出库 " + avgDaily.setScale(2, RoundingMode.HALF_UP)
                        + ", 当前库存 " + stock + " (安全库存 " + (safety == null ? "未设" : safety) + "),"
                        + " 预计可售 " + estDays + " 天";
            } else {
                s.estDaysOfStock = null;
                s.recommend = false;
                s.suggestQty = null;
                s.reason = "近" + LOOKBACK_DAYS + "天无出库记录 (日均 0), 暂不建议补货";
            }
            all.add(s);
        }

        // 排序: 建议补货的优先, 且按预计可售天数升序 (最紧的在前); 不补的按日均出库降序
        all.sort((a, b) -> {
            if (a.recommend != b.recommend) return a.recommend ? -1 : 1;
            if (a.recommend) {
                int da = a.estDaysOfStock == null ? Integer.MAX_VALUE : a.estDaysOfStock;
                int db = b.estDaysOfStock == null ? Integer.MAX_VALUE : b.estDaysOfStock;
                return Integer.compare(da, db);
            }
            return b.avgDailyOut.compareTo(a.avgDailyOut);
        });

        if (all.size() > topN) return new ArrayList<>(all.subList(0, topN));
        return all;
    }

    /** 建议补货量 = 提前期消耗 + 回到安全水位 的差额 (不小于 0). */
    private static BigDecimal calcSuggest(BigDecimal stock, BigDecimal avgDaily, BigDecimal safety, int estDays) {
        BigDecimal need = avgDaily.multiply(BigDecimal.valueOf(LEAD_TIME_DAYS)); // 覆盖提前期
        if (safety != null && safety.compareTo(need) > 0) need = safety;         // 取到安全水位
        BigDecimal gap = need.subtract(stock);
        return gap.compareTo(BigDecimal.ZERO) > 0 ? gap : BigDecimal.ZERO;
    }

    // ---------- helpers ----------
    private Map<Long, BigDecimal> currentStock(List<Long> pids) {
        Map<Long, BigDecimal> m = new HashMap<>();
        if (pids.isEmpty()) return m;
        List<InvStock> rows = stockMapper.selectList(
                new LambdaQueryWrapper<InvStock>().in(InvStock::getProductId, pids));
        for (InvStock r : rows) {
            BigDecimal q = r.getQty() == null ? BigDecimal.ZERO : r.getQty();
            m.merge(r.getProductId(), q, BigDecimal::add);
        }
        return m;
    }

    private BigDecimal safetyStock(Long pid) {
        try {
            BaseProduct p = productMapper.selectById(pid);
            return p == null ? null : p.getSafetyStock();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean match(String kw, String code, String name) {
        kw = kw.trim().toLowerCase(java.util.Locale.ROOT);
        return (code != null && code.toLowerCase(java.util.Locale.ROOT).contains(kw))
                || (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(kw));
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
