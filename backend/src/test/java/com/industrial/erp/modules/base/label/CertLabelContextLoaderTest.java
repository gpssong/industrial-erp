package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.base.label.context.CertLabelContextLoader;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.178 合格证 (CERT) 上下文。
 *
 * <p>这张标签是**对外承诺**（贴在食品接触塑料袋上、XK 检查会收的），所以口径比另三类严：
 * 用户 2026-11-10 的裁定是「严格只认 CHECKED 的 OQC，宁可报错也不打空白合格证」⇒
 * 四道闸（存在 / 是 OQC / 已审核 / 结论合格）全部要**抛**，一条都不能退成「回 null 让版面空着」。
 * 其中「结论合格」这一条不是用户点的，是本仓库自己的既有口径（{@code QmsInspectionService.isPassed:1077-1079}：
 * CHECKED + result∈{PASS, PASS_DISPOSITION} 才算放行），照抄而不是自创，见 {@link #failResultIsRejected}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertLabelContextLoaderTest {

    @Mock private MesQualityInspectionMapper inspectionMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private BaseProductUnitMapper productUnitMapper;
    @Mock private MesBatchMapper batchMapper;
    @Mock private PrdFinishedInMapper finishedInMapper;

    /** 一张「本来就能打」的 OQC：已审核 + 合格 + 有批号 + 源单是成品入库。 */
    private MesQualityInspection checkedOqc() {
        MesQualityInspection ins = new MesQualityInspection();
        ins.setId(501L);
        ins.setBillNo("QI202611060001");
        ins.setBillDate(LocalDate.of(2026, 11, 6));
        ins.setInspectionType("OQC");
        ins.setStatus("CHECKED");
        ins.setResult("PASS");
        ins.setProductId(77L);
        ins.setProductCode("FG001");
        ins.setProductName("背心袋 30×45cm");
        ins.setSpec("30×45cm");
        ins.setBatchNo("PH20261106A");
        ins.setQualifiedQty(new BigDecimal("12000"));
        ins.setSampleQty(new BigDecimal("20"));
        ins.setSourceBillType("PRD_FINISHED_IN");
        ins.setSourceBillId(900L);
        ins.setSourceBillNo("PFI202611060001");
        ins.setCheckTime(LocalDateTime.of(2026, 11, 7, 9, 30));
        return ins;
    }

    private CertLabelContextLoader loader() {
        return new CertLabelContextLoader(inspectionMapper, productMapper, productUnitMapper, batchMapper, finishedInMapper);
    }

    @Test
    @DisplayName("合格 OQC：键序 = 纸面次序，且四个主数据补缺字段真的补上")
    void checkedOqcBuildsFullContext() {
        when(inspectionMapper.selectById(501L)).thenReturn(checkedOqc());

        BaseProduct p = new BaseProduct();
        p.setId(77L);
        p.setMaterial("LDPE");
        p.setBarcode("6901234567890");
        p.setShelfLifeDays(365);
        when(productMapper.selectById(77L)).thenReturn(p);

        BaseProductUnit u = new BaseProductUnit();
        u.setUnitName("个");
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(u);

        MesBatch b = new MesBatch();
        b.setBatchNo("PH20261106A");
        b.setProductionDate(LocalDate.of(2026, 11, 5));
        when(batchMapper.selectOne(any())).thenReturn(b);

        PrdFinishedIn fin = new PrdFinishedIn();
        fin.setBillNo("PFI202611060001");
        fin.setPrdOrderNo("PD202611040002");
        when(finishedInMapper.selectById(900L)).thenReturn(fin);

        Map<String, Object> m = loader().load(501L, null).get(0);

        assertThat(m).containsOnlyKeys("productName", "productCode", "spec", "material", "barcode",
                "batchNo", "productionDate", "qty", "unitName", "shelfLifeDays",
                "inspectionBillNo", "inspectionDate", "orderNo");
        assertThat(m).containsEntry("qty", new BigDecimal("12000"));   // 合格数量，不是抽样数量
        assertThat(m).containsEntry("productionDate", LocalDate.of(2026, 11, 5));
        assertThat(m).containsEntry("unitName", "个");
        assertThat(m).containsEntry("material", "LDPE");
        assertThat(m).containsEntry("shelfLifeDays", 365);
        assertThat(m).containsEntry("inspectionBillNo", "QI202611060001");
        assertThat(m).containsEntry("inspectionDate", LocalDate.of(2026, 11, 7));
        assertThat(m).containsEntry("orderNo", "PD202611040002");
    }

    @Test
    @DisplayName("单不存在（含软删）→ 404，不是回一行空标签")
    void missingBillIs404() {
        when(inspectionMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> loader().load(9L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("出厂检验单不存在或已删除: id=9");
    }

    @Test
    @DisplayName("非 OQC（来料检验 IQC）→ 400 —— 打错单的合格证比空白更难发现")
    void nonOqcTypeIsRejected() {
        MesQualityInspection iqc = checkedOqc();
        iqc.setInspectionType("IQC");
        when(inspectionMapper.selectById(501L)).thenReturn(iqc);

        assertThatThrownBy(() -> loader().load(501L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合格证只挂出厂检验单(OQC)");
        verify(batchMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("OQC_RETURN（退货复检）不算出厂检验 —— 别复用 isFinishedInspection() 那种「两种都算」")
    void returnRecheckIsNotOqc() {
        MesQualityInspection ret = checkedOqc();
        ret.setInspectionType("OQC_RETURN");
        when(inspectionMapper.selectById(501L)).thenReturn(ret);

        assertThatThrownBy(() -> loader().load(501L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合格证只挂出厂检验单(OQC)");
    }

    @Test
    @DisplayName("未审核（DRAFT）→ 400，把状态报给人看，让人去审核而不是直接打")
    void draftIsRejected() {
        MesQualityInspection draft = checkedOqc();
        draft.setStatus("DRAFT");
        draft.setResult("HOLD");
        when(inspectionMapper.selectById(501L)).thenReturn(draft);

        assertThatThrownBy(() -> loader().load(501L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未审核")
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("已审核但结论不合格（FAIL / FAIL_REJECT / HOLD）→ 400 —— 口径抄 isPassed，只放行 PASS / PASS_DISPOSITION")
    void failResultIsRejected() {
        for (String result : List.of("FAIL", "FAIL_REJECT", "HOLD")) {
            MesQualityInspection ins = checkedOqc();
            ins.setResult(result);
            when(inspectionMapper.selectById(501L)).thenReturn(ins);

            assertThatThrownBy(() -> loader().load(501L, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("检验结论不合格");
        }
    }

    @Test
    @DisplayName("让步放行（PASS_DISPOSITION）仍是合格结论 → 照常出证")
    void concessionPassPrints() {
        MesQualityInspection ins = checkedOqc();
        ins.setResult("PASS_DISPOSITION");
        when(inspectionMapper.selectById(501L)).thenReturn(ins);

        assertThat(loader().load(501L, null)).hasSize(1);
    }

    @Test
    @DisplayName("源单不是成品入库（手工建单）→ 不查 prd_finished_in，orderNo 为空；证号本身照样出")
    void manualBillHasNoOrderNo() {
        MesQualityInspection ins = checkedOqc();
        ins.setSourceBillType(null);
        ins.setSourceBillId(null);
        when(inspectionMapper.selectById(501L)).thenReturn(ins);

        Map<String, Object> m = loader().load(501L, null).get(0);

        assertThat(m).containsEntry("orderNo", null);
        verify(finishedInMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("批号空 → 不查 mes_batch，生产日期留空（审核时手工填批号是合法路径，不能因此不出证）")
    void blankBatchSkipsBatchLookup() {
        MesQualityInspection ins = checkedOqc();
        ins.setBatchNo(null);
        when(inspectionMapper.selectById(501L)).thenReturn(ins);

        Map<String, Object> m = loader().load(501L, null).get(0);

        assertThat(m).containsEntry("productionDate", null);
        verify(batchMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("商品主数据缺配（无主单位 / 商品已删）→ 补缺字段留空但不炸，合格证照出")
    void toleratesMissingMasterData() {
        when(inspectionMapper.selectById(501L)).thenReturn(checkedOqc());
        when(productMapper.selectById(77L)).thenReturn(null);
        when(productUnitMapper.selectMainUnit(77L)).thenReturn(null);

        Map<String, Object> m = loader().load(501L, null).get(0);

        assertThat(m).containsEntry("material", null);
        assertThat(m).containsEntry("unitName", null);
    }

    @Test
    @DisplayName("示例数据与 load 同一份键清单、不含主数据键（主数据由 LabelContextService 并进来）")
    void sampleMatchesLoadContract() {
        List<Map<String, Object>> rows = loader().sample();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("productName", "productCode", "spec", "material", "barcode",
                "batchNo", "productionDate", "qty", "unitName", "shelfLifeDays",
                "inspectionBillNo", "inspectionDate", "orderNo");
        assertThat(rows.get(0)).doesNotContainKey("producerName");
        verify(inspectionMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("load 每次回新 map —— 复用静态样例会被 LabelContextService 并主数据时互相污染")
    void loadReturnsFreshMaps() {
        when(inspectionMapper.selectById(501L)).thenReturn(checkedOqc());

        Map<String, Object> a = loader().load(501L, null).get(0);
        Map<String, Object> b = loader().load(501L, null).get(0);
        a.put("producerName", "示例厂");

        assertThat(b).doesNotContainKey("producerName");
    }

    @Test
    @DisplayName("bizType() = CERT")
    void declaresCertBizType() {
        assertThat(loader().bizType()).isEqualTo(LabelBizType.CERT);
    }
}
