package com.industrial.erp.modules.base.label.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合格证 (CERT)：数据源 = 出厂检验单 {@code mes_quality_inspection} (inspection_type=OQC) + 商品主数据补缺。
 *
 * <p>四道闸缺一不可（用户 2026-11-10 裁定：「严格只认 CHECKED 的 OQC，宁可报错也不打空白合格证」）：
 * 单据存在 → 是 OQC → 已审核 → 结论合格。合格证是要贴到食品接触塑料袋上、XK 检查会收的东西，
 * 一张「日期空格、结论空格」的空白证比打不出来更糟：它看上去是有效的。所以这里全部走 {@link BizException}，
 * 没有一条退成「回 null 让版面空着」。
 *
 * <p>「结论合格」这条用的是本仓库既有口径，不是新发明的：{@code QmsInspectionService.isPassed()}
 * 只放行 {@code PASS / PASS_DISPOSITION}（让步放行仍是合格），这里逐字照抄。
 *
 * <p>刻意**不**复用 {@code QmsInspectionService.isFinishedInspection()} —— 那个判的是「OQC 或 OQC_RETURN」，
 * 退货复检出的证不叫出厂合格证。
 */
@Component
public class CertLabelContextLoader implements LabelContextLoader {

    public CertLabelContextLoader(MesQualityInspectionMapper inspectionMapper, BaseProductMapper productMapper,
                                  BaseProductUnitMapper productUnitMapper, MesBatchMapper batchMapper,
                                  PrdFinishedInMapper finishedInMapper) {
        this.inspectionMapper = inspectionMapper;
        this.productMapper = productMapper;
        this.productUnitMapper = productUnitMapper;
        this.batchMapper = batchMapper;
        this.finishedInMapper = finishedInMapper;
    }

    private final MesQualityInspectionMapper inspectionMapper;
    private final BaseProductMapper productMapper;
    private final BaseProductUnitMapper productUnitMapper;
    private final MesBatchMapper batchMapper;
    private final PrdFinishedInMapper finishedInMapper;

    @Override
    public LabelBizType bizType() { return LabelBizType.CERT; }

    @Override
    public List<Map<String, Object>> load(Long bizId, List<Long> lineIds) {
        MesQualityInspection ins = inspectionMapper.selectById(bizId);
        if (ins == null) throw BizException.of(404, "出厂检验单不存在或已删除: id=" + bizId);
        if (!Constants.INSPECTION_OQC.equals(ins.getInspectionType()))
            throw BizException.of(400, "合格证只挂出厂检验单(OQC)，当前单据类型=" + ins.getInspectionType()
                    + "（单号 " + ins.getBillNo() + "）");
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus()))
            throw BizException.of(400, "出厂检验单未审核，不出合格证: 单号=" + ins.getBillNo()
                    + " 状态=" + ins.getStatus());
        if (!isPassed(ins.getResult()))
            throw BizException.of(400, "检验结论不合格，不出合格证: 单号=" + ins.getBillNo()
                    + " 结论=" + ins.getResult());

        BaseProduct product = ins.getProductId() == null ? null : productMapper.selectById(ins.getProductId());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productName", ins.getProductName());
        m.put("productCode", ins.getProductCode());
        m.put("spec", ins.getSpec());
        m.put("material", product == null ? null : product.getMaterial());
        m.put("barcode", product == null ? null : product.getBarcode());
        m.put("batchNo", ins.getBatchNo());
        m.put("productionDate", productionDateOf(ins.getBatchNo()));
        // 数量 = 合格数量，不是抽样数量：证上写 20（样品数）而实际入库 12000 是个假账
        m.put("qty", ins.getQualifiedQty());
        m.put("unitName", unitNameOf(ins.getProductId()));
        m.put("shelfLifeDays", product == null ? null : product.getShelfLifeDays());
        m.put("inspectionBillNo", ins.getBillNo());
        // 检验日期只认审核时刻，不回落 bill_date —— 单据日期是「开单那天」，跟「检了合格」不是一回事。
        m.put("inspectionDate", ins.getCheckTime() == null ? null : ins.getCheckTime().toLocalDate());
        m.put("orderNo", orderNoOf(ins));
        return List.of(m);
    }

    /**
     * 示例数据：键与 {@link #load} 一一对应、顺序照抄。
     * 值用假单号（QI 前缀按 {@code Constants} 的 BILL_QI 口径），不拿真企业证号硬编。
     */
    @Override
    public List<Map<String, Object>> sample() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productName", "背心袋 30×45cm");
        row.put("productCode", "FG001");
        row.put("spec", "30×45cm");
        row.put("material", "LDPE");
        row.put("barcode", "6901234567890");
        row.put("batchNo", "PH20261106A");
        row.put("productionDate", LocalDate.of(2026, 11, 5));
        row.put("qty", new BigDecimal("12000"));
        row.put("unitName", "个");
        row.put("shelfLifeDays", 365);
        row.put("inspectionBillNo", "QI202611060001");
        row.put("inspectionDate", LocalDate.of(2026, 11, 7));
        row.put("orderNo", "PD202611040002");
        return List.of(row);
    }

    /** 照抄 {@code QmsInspectionService.isPassed()}（:1077-1079）：PASS / PASS_DISPOSITION 才算放行。 */
    private static boolean isPassed(String result) {
        return Constants.RESULT_PASS.equals(result) || Constants.RESULT_PASS_DISPOSITION.equals(result);
    }

    /** 生产日期不在检验单上，按批号回查 mes_batch（与 {@link FgRollLabelContextLoader} 同一口径）。 */
    private LocalDate productionDateOf(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) return null;
        MesBatch b = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, batchNo)
                .orderByDesc(MesBatch::getId)
                .last("LIMIT 1"));
        return b == null ? null : b.getProductionDate();
    }

    /** 检验单没有 unit_name 列 ⇒ 取商品主单位；没配主单位就留空，不为一个单位把整张证卡住。 */
    private String unitNameOf(Long productId) {
        if (productId == null) return null;
        BaseProductUnit u = productUnitMapper.selectMainUnit(productId);
        return u == null ? null : u.getUnitName();
    }

    /** 生产单号：OQC 由成品入库审核自动建单，sourceBillType=PRD_FINISHED_IN ⇒ 顺着源单取 prd_order_no。 */
    private String orderNoOf(MesQualityInspection ins) {
        if (!"PRD_FINISHED_IN".equals(ins.getSourceBillType()) || ins.getSourceBillId() == null) return null;
        PrdFinishedIn fin = finishedInMapper.selectById(ins.getSourceBillId());
        return fin == null ? null : fin.getPrdOrderNo();
    }
}
