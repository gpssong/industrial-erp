package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.industrial.erp.modules.base.controller.BaseLabelController;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.production.client.FeiePrintClient;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `/base/label/bills` 选单端点的契约。
 *
 * <p>存在理由：箱标的「出库日期」在库里**从来没有值**（`sal_delivery.delivery_date` 全库 165/165 NULL，
 * 建单与审核链路都不写它），纸面侧按 `bill_date` 回落。这里跟着回落是为了让屏幕和纸是同一个口径 ——
 * 但如实说：`billDate` 这一列**今天没有任何界面在渲染**（PC 下拉只拼单号+客户/品名+批号，
 * App 只显示单号），所以这几条钉的是**端点契约**，不是一个看得见的空列。纸面上那一栏空白才是真问题，
 * 那一条由 {@code CartonLabelContextLoaderTest} 钉。
 *
 * <p>v1.1.178 加了 CERT / GOODS_LABEL 后，这份契约多了一条形状规则：「一行 = 一个可打的标签候选」，
 * `billNo` 槽放的是**这一行在界面上的身份** —— 单证类放单号，商品标放商品编码。
 * CERT 当年在选单这步显式 400（A9 裁定 R2：宁可报错也别给一个点了必错的下拉项）；
 * 现在有了真 loader，同一个理由的正解从「不列」换成「只列打得出来的」，
 * 见 {@code certBillsAreFilteredToPrintableInspections}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseLabelControllerBillsTest {

    @BeforeAll
    static void installLambdaCaches() {
        // MyBatis-Plus 的 LambdaQueryWrapper 在 getSqlSegment() 时需要 lambda 列名解析，
        // 生产环境由 Spring 启动时 MybatisPlusAutoConfiguration 完成；单测 mock 环境要手动装。
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "test");
        LambdaUtils.installCache(TableInfoHelper.initTableInfo(assistant, MesQualityInspection.class));
        LambdaUtils.installCache(TableInfoHelper.initTableInfo(assistant, BaseProduct.class));
        LambdaUtils.installCache(TableInfoHelper.initTableInfo(assistant, SalDelivery.class));
    }

    @Mock private LabelPreviewService previewService;
    @Mock private LabelContextService contextService;
    @Mock private LabelPrintService printService;
    @Mock private PrdFinishedInMapper finishedInMapper;
    @Mock private PurReceiptMapper purReceiptMapper;
    @Mock private SalDeliveryMapper salDeliveryMapper;
    @Mock private BaseLabelTemplateService templateService;
    @Mock private SysFeiePrinterConfigMapper printerMapper;
    @Mock private FeiePrintClient feieClient;
    @Mock private LabelResolveService resolveService;
    @Mock private MesQualityInspectionMapper qualityInspectionMapper;
    @Mock private BaseProductMapper productMapper;

    private BaseLabelController controller;

    @BeforeEach
    void setUp() {
        controller = new BaseLabelController(null, templateService, previewService, contextService,
                printService, null, null, feieClient,
                finishedInMapper, purReceiptMapper, salDeliveryMapper, null, null, printerMapper, resolveService,
                qualityInspectionMapper, productMapper);
    }

    private SalDelivery delivery(LocalDate deliveryDate, LocalDate billDate) {
        SalDelivery s = new SalDelivery();
        s.setId(30L);
        s.setBillNo("CKP202611060001");
        s.setCustomerName("宁波某包装");
        s.setDeliveryDate(deliveryDate);
        s.setBillDate(billDate);
        s.setBillStatus("CHECKED");
        return s;
    }

    @Test
    @DisplayName("箱标选单：delivery_date 为空时日期列回落 bill_date（与纸面同一口径）")
    void cartonDateFallsBackToBillDate() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(delivery(null, LocalDate.of(2026, 11, 6))));

        List<Map<String, Object>> rows = controller.bills("CARTON", null, 30).getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("billDate", "2026-11-06");
    }

    @Test
    @DisplayName("出库日期有值时以它为准 —— 回落不能反过来盖掉真实出库日")
    void cartonDateKeepsDeliveryDateWhenPresent() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(delivery(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 6))));

        List<Map<String, Object>> rows = controller.bills("CARTON", null, 30).getData();

        assertThat(rows.get(0)).containsEntry("billDate", "2026-11-03");
    }

    private MesQualityInspection oqc(LocalDate checkTime) {
        MesQualityInspection ins = new MesQualityInspection();
        ins.setId(501L);
        ins.setBillNo("QI202611060001");
        ins.setBillDate(LocalDate.of(2026, 11, 6));
        ins.setInspectionType("OQC");
        ins.setStatus("CHECKED");
        ins.setResult("PASS");
        ins.setProductName("背心袋 30×45cm");
        ins.setBatchNo("PH20261106A");
        ins.setCheckTime(checkTime == null ? null : checkTime.atTime(9, 30));
        return ins;
    }

    @Test
    @DisplayName("CERT 选单只列「打得出来」的：OQC + CHECKED + 结论合格，三个条件一个都不能少")
    void certBillsAreFilteredToPrintableInspections() {
        when(qualityInspectionMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(oqc(LocalDate.of(2026, 11, 7))));

        controller.bills("CERT", null, 30);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<MesQualityInspection>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(qualityInspectionMapper).selectList(captor.capture());
        // 与 CertLabelContextLoader 的四道闸逐条对齐：下拉里躺一张点了必 400 的单，
        // 用户体验就是「系统在骗我有单」。installLambdaCaches 让 getSqlSegment() 可解析列名。
        String sql = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesQualityInspection>)
                captor.getValue()).getSqlSegment();
        assertThat(sql).contains("inspection_type = ");
        assertThat(sql).contains("status = ");
        assertThat(sql).contains("result IN (");
    }

    @Test
    @DisplayName("CERT 选单日期取 check_time（检验时刻），不是 bill_date（开单那天）")
    void certBillsUseCheckTimeAsDate() {
        when(qualityInspectionMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(oqc(LocalDate.of(2026, 11, 7))));

        Map<String, Object> row = controller.bills("CERT", null, 30).getData().get(0);

        assertThat(row).containsEntry("billDate", "2026-11-07");
        assertThat(row).containsEntry("billNo", "QI202611060001");
        assertThat(row).containsEntry("mainName", "背心袋 30×45cm");
        assertThat(row).containsEntry("batchNo", "PH20261106A");
    }

    @Test
    @DisplayName("check_time 为空（历史脏数据）→ 日期列回空串，不是 \"null\"（A9 裁定 R3 同一口径）")
    void certBillsTolerateNullCheckTime() {
        when(qualityInspectionMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(oqc(null)));

        assertThat(controller.bills("CERT", null, 30).getData().get(0)).containsEntry("billDate", "");
    }

    @Test
    @DisplayName("商品标选单：一行商品一张标，「单号」槽放商品编码 —— App 只显示 billNo，放 id 就是一串雪花数字")
    void goodsLabelBillsPutProductCodeInTheBillNoSlot() {
        BaseProduct p = new BaseProduct();
        p.setId(77L);
        p.setProductCode("FG001");
        p.setProductName("背心袋 30×45cm");
        p.setSpec("30×45cm");
        when(productMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(p));

        Map<String, Object> row = controller.bills("GOODS_LABEL", null, 30).getData().get(0);

        assertThat(row).containsEntry("id", 77L)
                .containsEntry("billNo", "FG001")
                .containsEntry("mainName", "背心袋 30×45cm");
        // 商品标没有单 ⇒ 没有日期、没有批号、没有单据状态；空串而不是 "null"
        assertThat(row).containsEntry("billDate", "")
                .doesNotContainKey("productName");
        assertThat(row).containsKeys("id", "billNo", "billDate", "mainName", "batchNo", "billStatus");
    }

    @Test
    @DisplayName("商品标 keyword 打编码/品名/规格，且不按 status 过滤（停用商品在库的还得贴标）")
    void goodsLabelKeywordCoversCodeNameSpec() {
        when(productMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        controller.bills("GOODS_LABEL", "背心", 30);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<BaseProduct>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(productMapper).selectList(captor.capture());
        String sql = ((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BaseProduct>)
                captor.getValue()).getSqlSegment();
        // keyword 打三个 LIKE：product_code LIKE、product_name LIKE、spec LIKE
        assertThat(sql).contains("product_code LIKE ");
        assertThat(sql).contains("product_name LIKE ");
        assertThat(sql).contains("spec LIKE ");
        assertThat(sql).doesNotContain("status");   // 不按 status 过滤
    }

    @Test
    @DisplayName("选单行的键名是前端契约：PC 下拉拼 billNo+mainName+batchNo，改名等于下拉凭空变空")
    void cartonRowKeysMatchWhatThePickerReads() {
        when(salDeliveryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(delivery(null, LocalDate.of(2026, 11, 6))));

        Map<String, Object> row = controller.bills("CARTON", null, 30).getData().get(0);

        assertThat(row).containsKeys("id", "billNo", "mainName", "batchNo", "billDate", "billStatus");
        assertThat(row).containsEntry("mainName", "宁波某包装");
    }

}
