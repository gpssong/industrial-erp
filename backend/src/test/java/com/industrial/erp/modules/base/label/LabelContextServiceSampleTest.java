package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.context.CartonLabelContextLoader;
import com.industrial.erp.modules.base.label.context.FgRollLabelContextLoader;
import com.industrial.erp.modules.base.label.context.LabelContextLoader;
import com.industrial.erp.modules.base.label.context.RawPalletLabelContextLoader;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * docs/36 Task 4：示例上下文（草稿编辑器打开就先有东西可看）。
 *
 * <p>**loader 与 context service 都用真实实现**，只有业务 mapper 和主数据 service 是 mock ——
 * 这条用例要证的正是「示例这条路一次业务表都不碰」，把 loader 也 mock 掉就等于什么都没测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelContextServiceSampleTest {

    @Mock private PrdFinishedInMapper finishedInMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private MesBatchMapper batchMapper;
    @Mock private PurReceiptMapper receiptMapper;
    @Mock private PurReceiptDetailMapper receiptDetailMapper;
    @Mock private SalDeliveryMapper deliveryMapper;
    @Mock private SalDeliveryDetailMapper deliveryDetailMapper;
    @Mock private LabelMasterService masterService;

    private LabelContextService service;

    /** 主数据返回**测试值**：示例数据里绝不硬编真实企业证号，也不拿示例盖掉用户已配的主数据。 */
    private LabelMaster master() {
        LabelMaster m = new LabelMaster();
        m.setProducerName("测试生产者");
        m.setProducerAddress("测试地址");
        m.setXkNo("测试证号");
        m.setExecStandard("测试标准");
        m.setStorageCondition("阴凉干燥");
        m.setInspectorCode("QC01");
        return m;
    }

    @BeforeEach
    void setUp() {
        when(masterService.load()).thenReturn(master());
        List<LabelContextLoader> loaders = List.of(
                new FgRollLabelContextLoader(finishedInMapper, productMapper, batchMapper),
                new RawPalletLabelContextLoader(receiptMapper, receiptDetailMapper),
                new CartonLabelContextLoader(deliveryMapper, deliveryDetailMapper));
        service = new LabelContextService(loaders, masterService);
    }

    @Test
    @DisplayName("成品卷标示例：业务字段是固定示例值，主数据六个键从 master 来，业务 mapper 一次都没碰")
    void fgRollSample() {
        Map<String, Object> row = service.loadSample("FG_ROLL").get(0);

        assertThat(row)
                .containsEntry("productName", "背心袋 30×45cm")
                .containsEntry("productCode", "FG001")
                .containsEntry("spec", "30×45cm")
                .containsEntry("unitName", "个")
                .containsEntry("qty", new BigDecimal("1200"))
                .containsEntry("batchNo", "PH20261106A")
                .containsEntry("orderNo", "PD202611060001")
                .containsEntry("billNo", "PFI202611060001")
                .containsEntry("productionDate", LocalDate.of(2026, 11, 6))
                .containsEntry("gramWeight", new BigDecimal("8"))
                .containsEntry("width", new BigDecimal("30"))
                .containsEntry("thickness", new BigDecimal("0.025"))
                .containsEntry("colorNo", "本色");
        assertMasterKeys(row);
        // 键集合 + 次序一起钉：多一个拼错的键、少一个键、或者 withMaster 退化成 HashMap，都得在这里红。
        // 次序 = load() 的 put 次序（load() 里后四个键只在查到商品时才写，示例永远给全），末尾六个是主数据。
        assertThat(row.keySet()).containsExactly(
                "productName", "productCode", "spec", "unitName", "qty", "batchNo",
                "orderNo", "billNo", "productionDate", "gramWeight", "width", "thickness", "colorNo",
                "producerName", "producerAddress", "xkNo", "execStandard", "storageCondition", "inspectorCode");
        verifyNoInteractions(finishedInMapper, productMapper, batchMapper);
    }

    @Test
    @DisplayName("原料垛标示例：供应商/入库单/报告号 + 品名批号数量，采购 mapper 一次都没碰")
    void rawPalletSample() {
        Map<String, Object> row = service.loadSample("RAW_PALLET").get(0);

        assertThat(row)
                .containsEntry("supplierName", "示例原料供应商")
                .containsEntry("receiptBillNo", "PR202611060001")
                .containsEntry("receiptDate", LocalDate.of(2026, 11, 6))
                .containsEntry("inspectionReportNo", "BG-YL-20261106-001")
                .containsEntry("productName", "低密度聚乙烯")
                .containsEntry("productCode", "RAW001")
                .containsEntry("unitName", "kg")
                .containsEntry("qty", new BigDecimal("1000"))
                .containsEntry("batchNo", "YL20261106A");
        assertMasterKeys(row);
        assertThat(row.keySet()).containsExactly(
                "supplierName", "receiptBillNo", "receiptDate", "inspectionReportNo",
                "productName", "productCode", "unitName", "qty", "batchNo",
                "producerName", "producerAddress", "xkNo", "execStandard", "storageCondition", "inspectorCode");
        verifyNoInteractions(receiptMapper, receiptDetailMapper);
    }

    @Test
    @DisplayName("箱标示例：客户/地址/电话/单号 + 品名规格数量，销售 mapper 一次都没碰")
    void cartonSample() {
        Map<String, Object> row = service.loadSample("CARTON").get(0);

        assertThat(row)
                .containsEntry("customerName", "示例客户")
                .containsEntry("deliveryAddress", "示例收货地址")
                .containsEntry("deliveryPhone", "00000000000")
                .containsEntry("orderNo", "SO202611060001")
                .containsEntry("deliveryBillNo", "SD202611060001")
                .containsEntry("deliveryDate", LocalDate.of(2026, 11, 6))
                .containsEntry("productName", "背心袋")
                .containsEntry("spec", "30×45cm")
                .containsEntry("unitName", "箱")
                .containsEntry("qty", new BigDecimal("20"))
                .containsEntry("batchNo", "PH20261106A");
        assertMasterKeys(row);
        // 同 fgRollSample：键集合 + 次序一起钉，次序 = CartonLabelContextLoader.load() 的 put 次序。
        assertThat(row.keySet()).containsExactly(
                "customerName", "deliveryAddress", "deliveryPhone", "orderNo", "deliveryBillNo",
                "deliveryDate", "productName", "spec", "unitName", "qty", "batchNo",
                "producerName", "producerAddress", "xkNo", "execStandard", "storageCondition", "inspectorCode");
        verifyNoInteractions(deliveryMapper, deliveryDetailMapper);
    }

    @Test
    @DisplayName("示例走和真实打印同一条路由 ⇒ 漏挂 loader 时同样是 400「还没有数据加载器」，不是空列表也不是 500")
    void certSampleIsExplicitlyUnimplemented() {
        // 和 LabelContextServiceTest.certWithoutLoaderThrows400 同一条口径：类型合法但这次没注册实现，
        // 就点名"没有加载器"。示例特有的那句留给"有加载器但没写 sample"（下一条用例）。
        assertThatThrownBy(() -> service.loadSample("CERT"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还没有数据加载器");
    }

    @Test
    @DisplayName("新加的 loader 忘了写 sample → 明确的 400，不是 AbstractMethodError/500")
    void loaderWithoutSampleFailsExplicitly() {
        // 这条直接调 noSample.sample()，不进 service 路由，bizType 选谁都不影响结论 —— 挑 CERT
        // 只是因为它是这批里最常被拿去做「新增类型」样例的那个，换成别的也不影响这条钉的东西。
        LabelContextLoader noSample = new LabelContextLoader() {
            @Override
            public LabelBizType bizType() { return LabelBizType.CERT; }

            @Override
            public List<Map<String, Object>> load(Long bizId, List<Long> lineIds) { return List.of(); }
        };

        assertThatThrownBy(noSample::sample)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该标签类型尚未配置示例数据");
    }

    @Test
    @DisplayName("未知 bizType 仍走 LabelBizType.of 的「不支持的标签类型」，不许被示例的 400 掩盖")
    void unknownBizTypeStillNamedAsUnsupported() {
        assertThatThrownBy(() -> service.loadSample("NO_SUCH_TYPE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的标签类型");
    }

    @Test
    @DisplayName("每次 loadSample 都给新 map：改上一次返回不能污染下一次，也不能污染 loader 自己的模板")
    void sampleReturnsAFreshMapEveryTime() {
        Map<String, Object> first = service.loadSample("FG_ROLL").get(0);
        first.put("productName", "被人改过了");
        first.remove("batchNo");

        assertThat(service.loadSample("FG_ROLL").get(0))
                .containsEntry("productName", "背心袋 30×45cm")
                .containsKey("batchNo");
    }

    @Test
    @DisplayName("每个 loader 自己的 sample() 也是每次新 map（这条只加在 service 上会被 withMaster 的拷贝掩盖）")
    void loadersReturnFreshMapsThemselves() {
        FgRollLabelContextLoader fgRoll = new FgRollLabelContextLoader(finishedInMapper, productMapper, batchMapper);
        RawPalletLabelContextLoader rawPallet =
                new RawPalletLabelContextLoader(receiptMapper, receiptDetailMapper);
        CartonLabelContextLoader carton = new CartonLabelContextLoader(deliveryMapper, deliveryDetailMapper);

        Map<String, Object> first = fgRoll.sample().get(0);
        first.put("productName", "被人改过了");
        assertThat(fgRoll.sample().get(0)).isNotSameAs(first)
                .containsEntry("productName", "背心袋 30×45cm");

        Map<String, Object> firstRaw = rawPallet.sample().get(0);
        firstRaw.put("supplierName", "被人改过了");
        assertThat(rawPallet.sample().get(0)).isNotSameAs(firstRaw)
                .containsEntry("supplierName", "示例原料供应商");

        Map<String, Object> firstCarton = carton.sample().get(0);
        firstCarton.put("customerName", "被人改过了");
        assertThat(carton.sample().get(0)).isNotSameAs(firstCarton)
                .containsEntry("customerName", "示例客户");

        verifyNoInteractions(finishedInMapper, productMapper, batchMapper,
                receiptMapper, receiptDetailMapper, deliveryMapper, deliveryDetailMapper);
    }

    @Test
    @DisplayName("用户还没配主数据 → 示例上下文里六个键仍是空串，不许拿示例值冒充企业名/证号")
    void emptyMasterStaysEmptyInSample() {
        // 单向的「不拿示例盖用户配置」挡不住反方向：主数据全空时，如果 withMaster 之外还有人
        // （loader 或以后的合并逻辑）往里塞"示例生产者/示例证号"，画布上就会印出一张假企业信息的标。
        when(masterService.load()).thenReturn(new LabelMaster());

        Map<String, Object> row = service.loadSample("FG_ROLL").get(0);

        assertThat(row)
                .containsEntry("producerName", "")
                .containsEntry("producerAddress", "")
                .containsEntry("xkNo", "")
                .containsEntry("execStandard", "")
                .containsEntry("storageCondition", "")
                .containsEntry("inspectorCode", "");
        // 业务键照常给示例值 —— 这条只锁主数据那六个。
        assertThat(row).containsEntry("productName", "背心袋 30×45cm");
    }

    private static void assertMasterKeys(Map<String, Object> row) {
        assertThat(row)
                .containsEntry("producerName", "测试生产者")
                .containsEntry("producerAddress", "测试地址")
                .containsEntry("xkNo", "测试证号")
                .containsEntry("execStandard", "测试标准")
                .containsEntry("storageCondition", "阴凉干燥")
                .containsEntry("inspectorCode", "QC01");
    }
}
