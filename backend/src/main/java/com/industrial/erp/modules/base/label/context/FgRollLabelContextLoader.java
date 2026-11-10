package com.industrial.erp.modules.base.label.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 成品卷标 (FG_ROLL)：数据源 prd_finished_in + base_product + mes_batch。单据即一行，故恒返回 1 项。 */
@Component
public class FgRollLabelContextLoader implements LabelContextLoader {

    public FgRollLabelContextLoader(PrdFinishedInMapper finishedInMapper, BaseProductMapper productMapper,
                                    MesBatchMapper batchMapper) {
        this.finishedInMapper = finishedInMapper;
        this.productMapper = productMapper;
        this.batchMapper = batchMapper;
    }

    private final PrdFinishedInMapper finishedInMapper;
    private final BaseProductMapper productMapper;
    private final MesBatchMapper batchMapper;

    @Override
    public LabelBizType bizType() { return LabelBizType.FG_ROLL; }

    @Override
    public List<Map<String, Object>> load(Long bizId, List<Long> lineIds) {
        PrdFinishedIn in = finishedInMapper.selectById(bizId);
        if (in == null) throw BizException.of(404, "成品入库单不存在或已删除: id=" + bizId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productName", in.getProductName());
        m.put("productCode", in.getProductCode());
        m.put("spec", in.getSpec());
        m.put("unitName", in.getUnitName());
        m.put("qty", in.getQty());
        m.put("batchNo", in.getBatchNo());
        m.put("orderNo", in.getPrdOrderNo());
        m.put("billNo", in.getBillNo());
        m.put("productionDate", productionDateOf(in.getBatchNo()));

        if (in.getProductId() != null) {
            BaseProduct p = productMapper.selectById(in.getProductId());
            if (p != null) {
                m.put("gramWeight", p.getGramWeight());
                m.put("width", p.getWidth());
                m.put("thickness", p.getThickness());
                m.put("colorNo", p.getColorNo());
            }
        }
        return List.of(m);
    }

    /**
     * 示例数据：字段与 {@link #load} 一一对应，键顺序也照抄，画布上看到的列次序就是纸上的。
     * 值只用字典里存在的字段名能表达的东西，不为了填示例去扩数据库列。
     */
    @Override
    public List<Map<String, Object>> sample() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productName", "背心袋 30×45cm");
        row.put("productCode", "FG001");
        row.put("spec", "30×45cm");
        row.put("unitName", "个");
        row.put("qty", new BigDecimal("1200"));
        row.put("batchNo", "PH20261106A");
        row.put("orderNo", "PD202611060001");
        row.put("billNo", "PFI202611060001");
        row.put("productionDate", LocalDate.of(2026, 11, 6));
        row.put("gramWeight", new BigDecimal("8"));
        row.put("width", new BigDecimal("30"));
        row.put("thickness", new BigDecimal("0.025"));
        row.put("colorNo", "本色");
        return List.of(row);
    }

    /** 生产日期不在 prd_finished_in 上（该表没有 prod_date 列，docs/32 §3.2 已核对），按批号回查 mes_batch。 */
    private LocalDate productionDateOf(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) return null;
        MesBatch b = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, batchNo)
                .orderByDesc(MesBatch::getId)
                .last("LIMIT 1"));
        return b == null ? null : b.getProductionDate();
    }
}
