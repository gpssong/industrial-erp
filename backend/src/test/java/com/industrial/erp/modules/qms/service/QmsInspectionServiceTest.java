package com.industrial.erp.modules.qms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.entity.MesBatchLink;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.*;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdSemiIn;
import com.industrial.erp.modules.production.mapper.*;
import com.industrial.erp.modules.purchase.entity.PurOrderDetail;
import com.industrial.erp.modules.purchase.mapper.*;
import com.industrial.erp.modules.qms.dto.InspectionCreateDTO;
import com.industrial.erp.modules.qms.dto.ProcessInspectionRecordDTO;
import com.industrial.erp.modules.qms.entity.*;
import com.industrial.erp.modules.qms.mapper.*;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QmsInspectionServiceTest {
    /** v1.1.149 默认判「不适用」的 4 项 (本公司不做印刷). */
    private static final Set<String> NA_CODES = Set.of(
            "PAT_CORONA", "OQC_FILM_CORONA", "BAG_PRINT", "OQC_BAG_PRINT");

    private final MesQualityInspectionMapper inspections = mock(MesQualityInspectionMapper.class);
    private final QmsIqcItemMapper items = mock(QmsIqcItemMapper.class);
    private final PrdProcessMapper processes = mock(PrdProcessMapper.class);
    private final PrdOrderMapper orders = mock(PrdOrderMapper.class);
    private final BaseProductMapper products = mock(BaseProductMapper.class);
    private final QmsIqcItemService itemService = mock(QmsIqcItemService.class);
    private final MesBatchMapper batches = mock(MesBatchMapper.class);
    private final MesBatchLinkMapper links = mock(MesBatchLinkMapper.class);
    private final SysOperLogMapper audit = mock(SysOperLogMapper.class);
    private final PrdSemiInMapper semiIns = mock(PrdSemiInMapper.class);
    private final PrdFinishedInMapper finishedIns = mock(PrdFinishedInMapper.class);
    private final PurOrderDetailMapper purOrderDetails = mock(PurOrderDetailMapper.class);
    private final BillNoGenerator numbers = mock(BillNoGenerator.class);
    private final Map<Long, MesQualityInspection> heads = new ConcurrentHashMap<>();
    private final Map<Long, List<QmsIqcItem>> rows = new ConcurrentHashMap<>();
    private final Map<Long, PrdProcess> processRows = new ConcurrentHashMap<>();
    private final Map<Long, PrdFinishedIn> finishedInRows = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(9000);
    private final ReentrantLock processLock = new ReentrantLock();
    private boolean emulateRowLock;
    private PrdOrder order;
    private PrdProcess bag;
    private BaseProduct product;
    private QmsInspectionService service;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(MesQualityInspection.class, QmsIqcItem.class, PrdProcess.class, MesBatch.class,
                PrdFinishedIn.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "qms-test"), type);
        }
        SecurityContext.setCurrentUserIdForTest(77L);
        service = new QmsInspectionService(inspections, mock(MesQualityDefectMapper.class), batches, links, audit,
                numbers, mock(PurReceiptDetailMapper.class), mock(PurReceiptMapper.class), items,
                mock(QmsIqcAttachmentMapper.class), products, mock(PurInboundPlanMapper.class),
                mock(PurOrderMapper.class), purOrderDetails, semiIns, finishedIns,
                orders, processes, itemService);
        order = new PrdOrder();
        order.setId(10L);
        order.setBillNo("PRD-10");
        order.setBillStatus("RELEASED");
        order.setProductId(20L);
        order.setProductName("真实生产产品");
        order.setProductCode("P20");
        order.setSpec("订单规格");
        order.setBatchNo("BATCH-10");
        bag = process(100L, "制袋一工序");
        product = new BaseProduct();
        product.setId(20L);
        // v1.1.150: 首检/巡检/成品检只对食品级商品建单, 默认夹具按食品级; 工业品用例显式置 0.
        product.setIsFoodGrade(1);
        when(orders.selectById(10L)).thenAnswer(inv -> order);
        when(products.selectById(20L)).thenAnswer(inv -> product);
        when(processes.selectById(anyLong())).thenAnswer(inv -> processRows.get(inv.getArgument(0)));
        when(processes.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> query = inv.getArgument(0);
            assertThat(query.getSqlSegment()).contains("FOR UPDATE", "id");
            if (emulateRowLock) processLock.lock();
            Collection<Object> values = query.getParamNameValuePairs().values();
            return processRows.values().stream().filter(p -> values.contains(p.getId())).findFirst().orElse(null);
        });
        when(processes.selectList(any())).thenAnswer(inv -> new ArrayList<>(processRows.values()));
        when(processes.selectBatchIds(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return processRows.values().stream()
                    .filter(p -> ids.contains(p.getId())).collect(Collectors.toList());
        });
        when(finishedIns.selectById(anyLong())).thenAnswer(inv -> finishedInRows.get(inv.getArgument(0)));
        when(finishedIns.selectList(any())).thenAnswer(inv -> new ArrayList<>(finishedInRows.values()));
        when(numbers.generate(anyString())).thenAnswer(inv -> "QI-" + sequence.incrementAndGet());
        when(inspections.insert(any(MesQualityInspection.class))).thenAnswer(inv -> {
            MesQualityInspection head = inv.getArgument(0);
            head.setId(sequence.incrementAndGet());
            heads.put(head.getId(), copyHead(head));
            return 1;
        });
        when(inspections.selectById(anyLong())).thenAnswer(inv -> copyHead(heads.get(inv.getArgument(0))));
        when(inspections.selectByIdForUpdate(anyLong())).thenAnswer(inv -> copyHead(heads.get(inv.getArgument(0))));
        when(inspections.updateById(any(MesQualityInspection.class))).thenAnswer(inv -> {
            MesQualityInspection head = inv.getArgument(0);
            heads.put(head.getId(), copyHead(head));
            return 1;
        });
        when(inspections.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> query = inv.getArgument(0);
            query.getSqlSegment();
            Collection<Object> values = query.getParamNameValuePairs().values();
            return heads.values().stream().filter(h -> values.contains(h.getSourceBillType())
                    && values.contains(h.getSourceBillId()) && values.contains(h.getInspectionType()))
                    .max(Comparator.comparing(MesQualityInspection::getId)).map(this::copyHead).orElse(null);
        });
        when(items.selectByIqcId(anyLong())).thenAnswer(inv -> rows.getOrDefault(inv.getArgument(0), List.of())
                .stream().map(this::copyItem).collect(Collectors.toList()));
        when(items.insert(any(QmsIqcItem.class))).thenAnswer(inv -> {
            QmsIqcItem item = inv.getArgument(0);
            item.setId(sequence.incrementAndGet());
            rows.computeIfAbsent(item.getIqcId(), key -> new ArrayList<>()).add(copyItem(item));
            return 1;
        });
        doAnswer(inv -> {
            Long id = inv.getArgument(0);
            List<QmsIqcItem> submitted = inv.getArgument(1);
            Map<String, QmsIqcItem> byCode = QmsIqcItemService.validateTemplateSubmission(rows.get(id), submitted, "FAI");
            rows.get(id).forEach(row -> {
                QmsIqcItem input = byCode.get(row.getItemCode());
                row.setResultValue(input.getResultValue());
                row.setJudgmentBasis(input.getJudgmentBasis());
                row.setPassFlag(input.getPassFlag() == null ? -1 : input.getPassFlag());
            });
            return null;
        }).when(itemService).batchSave(anyLong(), anyList());
    }

    @AfterEach
    void cleanUp() { SecurityContext.clearForTest(); }

    @ParameterizedTest
    @CsvSource({"制袋,FAI,13,BAG", "制袋,PAT,11,BAG", "吹膜,FAI,8,BLOW", "吹膜,PAT,11,BLOW"})
    void createsIndependentTemplatesWithUntestedItems(String name, String type, int count, String stage) {
        bag.setProcessName(name);
        if (name.equals("制袋") && type.equals("PAT")) signFai();
        Map<String, Object> detail = service.ensureForProcess(10L, 100L, type);
        MesQualityInspection head = (MesQualityInspection) detail.get("head");
        assertThat(detail).containsEntry("stage", stage).containsEntry("order", order).containsEntry("process", bag);
        assertThat(rows.get(head.getId())).hasSize(count).allSatisfy(it -> {
            // v1.1.149: 达因/印刷质量 4 项默认不适用, 其余默认合格; 实测值仍空着手填
            assertThat(it.getPassFlag()).isEqualTo(NA_CODES.contains(it.getItemCode()) ? 2 : 1);
            assertThat(it.getResultValue()).isNull();
            assertThat(it.getSource()).isEqualTo("TEMPLATE");
            assertThat(it.getItemCode().startsWith("BAG_")).isEqualTo(stage.equals("BAG"));
            assertThat(it.getRequirement()).doesNotContain("±0.003", "±2mm", "≥38", "120mg/kg");
            // v1.1.134 同日修补: 模板口径改按企业标准 Q/ZPC 1-2017, 每项须能追溯到条款/表号/试验方法出处
            assertThat(it.getItemName() + " " + it.getRequirement() + " " + it.getMethod()).containsAnyOf(
                    "表1", "表2", "表3", "表4", "表5", "表6", "3.1", "3.2", "3.3", "3.4", "3.5",
                    "4.1", "4.3", "5.1", "5.2", "5.3", "5.4", "6.1", "6.2", "6.3", "6.4",
                    "GB", "QB", "JJF", "工艺卡", "工单", "放行", "图样");
            // 蒸发残渣一类迁移指标只允许核验第三方报告, 不得写成机台现场实测
            if (it.getRequirement().contains("蒸发残渣")) assertThat(it.getRequirement()).contains("报告");
        });
        assertThat(rows.get(head.getId()).stream().filter(it -> it.getIsFoodGradeItem() == 1).count())
                .isEqualTo(stage.equals("BAG") ? 3 : type.equals("FAI") ? 2 : 4);
        if (stage.equals("BLOW") && type.equals("FAI")) {
            assertThat(rows.get(head.getId())).extracting(QmsIqcItem::getItemCode).doesNotContain(
                    "BLOW_ELONGATION", "BLOW_HAZE", "EVAP_RESIDUE", "KMnO4_CONSUMPTION",
                    "HEAVY_METAL_MIG", "FOOD_CONTACT_MARK", "FOOD_MIGRATION_REPORT");
        }
        assertThat(head.getProductId()).isEqualTo(order.getProductId());
        assertThat(head.getSourceBillNo()).isEqualTo(order.getBillNo());
        assertThat(head.getBatchNo()).isEqualTo(order.getBatchNo());
        assertThat(head.getWorkshop()).isEqualTo(bag.getWorkshop());
        assertThat(head.getRemark()).contains("真实操作员");
        assertThat(head.getStatus()).isEqualTo("DRAFT");
        assertThat(head.getResult()).isEqualTo("HOLD");
        assertThat(head.getQualifiedQty()).isNull();
    }

    /**
     * sql/143 把老模板项整单软删后, App 只调 ensure-for-process (不调 /{id}/ensure-template),
     * 空明细草稿必须在同一接口里按最新模板重灌, 否则签核会卡在「检验单无模板项」.
     */
    @Test
    void reSeedsEmptyDraftThroughEnsureForProcess() {
        bag.setProcessName("吹膜");
        Long headId = ((MesQualityInspection) service.ensureForProcess(10L, 100L, "FAI").get("head")).getId();
        assertThat(rows.get(headId)).hasSize(8);
        rows.get(headId).clear();
        Map<String, Object> again = service.ensureForProcess(10L, 100L, "FAI");
        assertThat(((MesQualityInspection) again.get("head")).getId()).isEqualTo(headId);
        assertThat((List<QmsIqcItem>) again.get("items")).extracting(QmsIqcItem::getItemCode).containsExactlyInAnyOrder(
                "BLOW_MATERIAL_CHECK", "BLOW_APPEARANCE", "BLOW_THICKNESS", "BLOW_WIDTH",
                "BLOW_TENSILE", "BLOW_ODOR", "FOOD_COMPLIANCE_DOC", "FOOD_HYGIENE");
    }

    @Test
    void doesNotGuessStageFromWorkshopOrSeedUnknownProcess() {
        bag.setProcessName("分切");
        bag.setWorkshop("制袋吹膜车间");
        assertThat(service.isBagProcess(bag)).isFalse();
        assertThat(service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThatThrownBy(() -> service.ensureForProcess(10L, 100L, "FAI")).hasMessageContaining("无法识别");
        verify(inspections, never()).insert(any(MesQualityInspection.class));
        verify(items, never()).insert(any(QmsIqcItem.class));
    }

    @Test
    void listsOnlyExpandedRecognizedProcessesAndRequiresExistingOrder() {
        process(101L, "吹膜");
        process(102L, "印刷");
        process(103L, "制袋手工").setFromDefId(null);
        process(104L, "制袋已删").setDeleted(1);
        assertThat(service.listInspectionProcesses(10L)).extracting(PrdProcess::getId).containsExactlyInAnyOrder(100L, 101L);
        assertThatThrownBy(() -> service.listInspectionProcesses(999L)).hasMessageContaining("生产单不存在");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CHECKED", "FINISHED", "CLOSED", "CANCELLED"})
    void rejectsInactiveOrder(String status) {
        order.setBillStatus(status);
        assertThatThrownBy(() -> service.ensureForProcess(10L, 100L, "FAI")).hasMessageContaining("RELEASED/PRODUCING");
        verify(inspections, never()).insert(any(MesQualityInspection.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASED", "PRODUCING"})
    void allowsFirstTrialBeforeAnyReport(String status) {
        order.setBillStatus(status);
        assertThat(bag.getStatus()).isZero();
        Long fai = signFai();
        assertThat(service.hasPassedFaiForProcess(100L)).isTrue();
        assertThat(ensure("PAT").getId()).isNotEqualTo(fai);
        assertThat(bag.getStatus()).isZero();
    }

    @Test
    void rejectsOrderMismatchAndInvalidType() {
        bag.setPrdOrderId(11L);
        assertThatThrownBy(() -> ensure("FAI")).hasMessageContaining("不属于");
        assertThatThrownBy(() -> ensure("IQC")).hasMessageContaining("FAI 或 PAT");
        verify(inspections, never()).insert(any(MesQualityInspection.class));
    }

    @Test
    void sameOrderDifferentProcessesHaveSeparateFaiAndGate() {
        Long first = signFai();
        process(101L, "制袋二工序");
        MesQualityInspection second = (MesQualityInspection) service.ensureForProcess(10L, 101L, "FAI").get("head");
        assertThat(second.getId()).isNotEqualTo(first);
        assertThat(service.hasPassedFaiForProcess(100L)).isTrue();
        assertThat(service.hasPassedFaiForProcess(101L)).isFalse();
        assertThatThrownBy(() -> service.ensureForProcess(10L, 101L, "PAT")).hasMessageContaining("本工序首检");
    }

    @Test
    void draftAndSignedFaiAreIdempotentAndNeverReplaced() {
        MesQualityInspection head = ensure("FAI");
        List<Long> ids = rows.get(head.getId()).stream().map(QmsIqcItem::getId).toList();
        assertThat(ensure("FAI").getId()).isEqualTo(head.getId());
        service.saveProcessRecord(head.getId(), record(head.getId(), "PASS"));
        assertThat(ensure("FAI").getStatus()).isEqualTo("CHECKED");
        assertThat(rows.get(head.getId())).extracting(QmsIqcItem::getId).containsExactlyElementsOf(ids);
        verify(inspections, times(1)).insert(any(MesQualityInspection.class));
    }

    @Test
    void processLockSerializesConcurrentEnsureCalls() throws Exception {
        emulateRowLock = true;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> create = () -> {
            SecurityContext.setCurrentUserIdForTest(77L);
            try {
                start.await();
                return ensure("FAI").getId();
            } finally {
                if (processLock.isHeldByCurrentThread()) processLock.unlock();
                SecurityContext.clearForTest();
            }
        };
        try {
            Future<Long> first = executor.submit(create);
            Future<Long> second = executor.submit(create);
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            verify(inspections, times(1)).insert(any(MesQualityInspection.class));
            ArgumentCaptor<AbstractWrapper> query = ArgumentCaptor.forClass(AbstractWrapper.class);
            verify(inspections, times(2)).selectOne(query.capture());
            assertThat(query.getAllValues()).allSatisfy(q -> assertThat(q.getSqlSegment()).contains("FOR UPDATE"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void patDraftIsReusedAndCheckedPatCreatesNextRoundWithoutDeletingHistory() {
        signFai();
        MesQualityInspection pat = ensure("PAT");
        assertThat(ensure("PAT").getId()).isEqualTo(pat.getId());
        service.saveProcessRecord(pat.getId(), record(pat.getId(), "PASS"));
        MesQualityInspection next = ensure("PAT");
        assertThat(next.getId()).isNotEqualTo(pat.getId());
        assertThat(heads.get(pat.getId()).getStatus()).isEqualTo("CHECKED");
        assertThat(rows.get(pat.getId())).allSatisfy(it -> assertThat(it.getPassFlag()).isEqualTo(1));
        assertThat(rows.get(next.getId())).allSatisfy(it -> assertThat(it.getPassFlag())
                .isEqualTo(NA_CODES.contains(it.getItemCode()) ? 2 : 1));
        verify(items, never()).deleteByIqcId(anyLong());
    }

    @Test
    void bagPatRequiresPassedFaiButBlowPatRemainsCompatible() {
        assertThatThrownBy(() -> ensure("PAT")).hasMessageContaining("尚无已签核 PASS");
        bag.setProcessName("吹膜");
        assertThat(ensure("PAT").getId()).isNotNull();
    }

    @Test
    void reportLoadsRealProcessInsteadOfTrustingCallerAndDoesNotOverwriteMeasurements() {
        bag.setProcessName("吹膜");
        PrdProcess caller = new PrdProcess();
        caller.setId(100L);
        caller.setProcessName("制袋伪造");
        Long id = service.createOnReport(caller, order, BigDecimal.TEN, BigDecimal.ONE);
        assertThat(rows.get(id)).hasSize(11).noneSatisfy(it -> assertThat(it.getItemCode()).startsWith("BAG_"));
        service.saveProcessRecord(id, record(id, null));
        service.createOnReport(caller, order, new BigDecimal("1000"), BigDecimal.ZERO);
        assertThat(heads.get(id).getSampleQty()).isEqualByComparingTo("5");
        verify(inspections, times(1)).insert(any(MesQualityInspection.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void untestedOrFailedItemCannotPassViaRecordOrGenericCheck(int flag) {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.getItems().get(0).setPassFlag(flag);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).isInstanceOf(BizException.class);
        measuredInStore(id);
        rows.get(id).get(0).setPassFlag(flag);
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).isInstanceOf(BizException.class);
        assertThat(heads.get(id).getStatus()).isEqualTo("DRAFT");
        verify(links, never()).insert(any(MesBatchLink.class));
    }

    @Test
    void everyItemRequiresActualResultEvenWhenFlagIsPass() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.getItems().get(0).setResultValue("  ");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("未完成实测");
        measuredInStore(id);
        rows.get(id).get(0).setResultValue(null);
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).hasMessageContaining("未完成实测");
    }

    // ==================================================================
    // v1.1.149 本公司不做印刷: 达因/印刷质量 4 项默认「不适用」, 其余默认「合格」
    // ==================================================================

    @Test
    void templatesDefaultNaOnlyForCoronaAndPrintItems() {
        Long bagFai = ensure("FAI").getId();
        assertThat(rows.get(bagFai)).filteredOn(it -> NA_CODES.contains(it.getItemCode()))
                .singleElement().satisfies(it -> {
                    assertThat(it.getItemCode()).isEqualTo("BAG_PRINT");
                    assertThat(it.getPassFlag()).isEqualTo(2);
                });
        assertThat(rows.get(bagFai)).filteredOn(it -> !NA_CODES.contains(it.getItemCode()))
                .allSatisfy(it -> assertThat(it.getPassFlag()).isEqualTo(1));

        bag.setProcessName("吹膜");
        Long blowPat = ensure("PAT").getId();
        assertThat(rows.get(blowPat)).filteredOn(it -> "PAT_CORONA".equals(it.getItemCode()))
                .singleElement().satisfies(it -> assertThat(it.getPassFlag()).isEqualTo(2));

        Long filmOqc = ((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId();
        assertThat(rows.get(filmOqc)).filteredOn(it -> "OQC_FILM_CORONA".equals(it.getItemCode()))
                .singleElement().satisfies(it -> assertThat(it.getPassFlag()).isEqualTo(2));
        assertThat(rows.get(filmOqc)).filteredOn(it -> !NA_CODES.contains(it.getItemCode()))
                .allSatisfy(it -> assertThat(it.getPassFlag()).isEqualTo(1));
    }

    @Test
    void naItemSignsWithoutActualValueWhileOtherItemsStillNeedIt() {
        bag.setProcessName("吹膜");
        signFai();
        Long id = ensure("PAT").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.getItems().forEach(it -> {
            if ("PAT_CORONA".equals(it.getItemCode())) {
                it.setPassFlag(2);
                it.setResultValue(null);
            }
        });
        service.saveProcessRecord(id, dto);
        assertThat(heads.get(id).getStatus()).isEqualTo("CHECKED");
    }

    @Test
    void onlyCoronaAndPrintItemsMayBeMarkedNotApplicable() {
        bag.setProcessName("吹膜");
        signFai();
        Long id = ensure("PAT").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.getItems().forEach(it -> {
            if ("PAT_CORONA".equals(it.getItemCode())) {
                it.setPassFlag(2);
                it.setResultValue(null);
            }
            if ("PAT_APPEARANCE".equals(it.getItemCode())) it.setPassFlag(2);
        });
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("不能判不适用");
    }

    @Test
    void oqcReleaseAcceptsNaPrintItemWithoutMeasuredValue() {
        Long id = ((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId();
        MesQualityInspection head = heads.get(id);
        head.setSampleQty(new BigDecimal("5"));
        head.setQualifiedQty(new BigDecimal("5"));
        head.setUnqualifiedQty(BigDecimal.ZERO);
        rows.get(id).forEach(it -> {
            if (!"OQC_BAG_PRINT".equals(it.getItemCode())) {
                it.setPassFlag(1);
                it.setResultValue("实测记录");
            }
        });
        assertThat(rows.get(id)).filteredOn(it -> "OQC_BAG_PRINT".equals(it.getItemCode()))
                .singleElement().satisfies(it -> {
                    assertThat(it.getPassFlag()).isEqualTo(2);
                    assertThat(it.getResultValue()).isNull();
                });
        service.check(id, "PASS", "NONE", "非印刷袋按5.2.1.3注略去印刷质量", null, null, null, null, null,
                null, null, null, null);
        assertThat(heads.get(id).getStatus()).isEqualTo("CHECKED");
    }

    @Test
    void missingEmptyDuplicateAndManualItemsCannotBypassTemplate() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.getItems().remove(0);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("完整检验项");
        dto.setItems(List.of());
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("完整检验项");
        dto.setItems(null);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("完整检验项");
        dto.setItems(record(id, "PASS").getItems());
        dto.getItems().get(1).setItemCode(dto.getItems().get(0).getItemCode());
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("重复");
        measuredInStore(id);
        rows.put(id, new ArrayList<>(List.of(rows.get(id).get(0))));
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).hasMessageContaining("代码集合不完整");
        rows.get(id).get(0).setSource("MANUAL");
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).hasMessageContaining("手工项替代");
        rows.put(id, new ArrayList<>());
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).hasMessageContaining("无模板项");
        verify(items, times(13)).insert(any(QmsIqcItem.class));
    }

    @Test
    void blowFaiCannotCountAsBagFaiOrBeSignedAfterProcessChanged() {
        bag.setProcessName("吹膜");
        Long id = signFai();
        bag.setProcessName("制袋");
        assertThat(service.hasPassedFaiForProcess(100L)).isFalse();
        heads.get(id).setStatus("DRAFT");
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).hasMessageContaining("BAG工序不匹配");
    }

    @Test
    void semiInFaiWithSameNumericSourceIdDoesNotOpenBagGate() {
        MesQualityInspection semi = new MesQualityInspection();
        semi.setId(999L);
        semi.setSourceBillType("PRD_SEMI_IN");
        semi.setSourceBillId(100L);
        semi.setInspectionType("FAI");
        semi.setStatus("CHECKED");
        semi.setResult("PASS");
        heads.put(999L, semi);
        assertThat(service.hasPassedFaiForProcess(100L)).isFalse();
        assertThatThrownBy(() -> ensure("PAT")).hasMessageContaining("本工序首检");
    }

    // ==================================================================
    // v1.1.138 App 吹膜首检改为按工单+工序, 半成品入库门禁要认工序侧那张
    // ==================================================================

    @Test
    void semiInGateAcceptsSignedBlowFaiOnOrderProcess() {
        // 吹膜工序上首检签合格 (App 现场顺序: 开工 → 首检 → 半成品入库, 入库单后建)
        bag.setProcessName("吹膜");
        Long faiId = signFai();
        assertThat(heads.get(faiId).getSourceBillType()).isEqualTo("PRD_PROCESS");
        assertThat(heads.get(faiId).getStatus()).isEqualTo("CHECKED");

        // 半成品入库单挂在该工单下, 但自身没有 FAI → 门禁应认工序侧那张
        PrdSemiIn semiIn = new PrdSemiIn();
        semiIn.setId(500L);
        semiIn.setBillNo("PSI-500");
        semiIn.setPrdOrderId(10L);
        when(semiIns.selectById(500L)).thenAnswer(inv -> semiIn);

        assertThat(service.hasPassedFai(500L)).isTrue();
        MesQualityInspection linked = service.findLinkedFaiForSemiIn(500L);
        assertThat(linked).isNotNull();
        assertThat(linked.getId()).isEqualTo(faiId);
        assertThat(linked.getSourceBillType()).isEqualTo("PRD_PROCESS");
    }

    @Test
    void semiInGateStillRejectsUnsignedOrFailedBlowFai() {
        bag.setProcessName("吹膜");
        PrdSemiIn semiIn = new PrdSemiIn();
        semiIn.setId(500L);
        semiIn.setBillNo("PSI-500");
        semiIn.setPrdOrderId(10L);
        when(semiIns.selectById(500L)).thenAnswer(inv -> semiIn);

        // 草稿态不算合格
        ensure("FAI");
        assertThat(service.hasPassedFai(500L)).isFalse();

        // 签核不合格也不放行
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO fail = record(id, "FAIL");
        fail.getItems().get(0).setPassFlag(0);
        fail.getItems().get(0).setResultValue("实测不合格");
        service.saveProcessRecord(id, fail);
        assertThat(service.hasPassedFai(500L)).isFalse();
    }

    @Test
    void semiInGateFallsBackToOwnFaiWhenLinked() {
        // 入库单自己挂着已签合格的 FAI (存量单/PC 侧按入库单首检) → 照旧放行
        PrdSemiIn semiIn = new PrdSemiIn();
        semiIn.setId(500L);
        semiIn.setBillNo("PSI-500");
        semiIn.setPrdOrderId(10L);
        when(semiIns.selectById(500L)).thenAnswer(inv -> semiIn);
        MesQualityInspection own = new MesQualityInspection();
        own.setId(700L);
        own.setSourceBillType("PRD_SEMI_IN");
        own.setSourceBillId(500L);
        own.setInspectionType("FAI");
        own.setStatus("CHECKED");
        own.setResult("PASS");
        heads.put(700L, own);
        assertThat(service.hasPassedFai(500L)).isTrue();
        assertThat(service.findLinkedFaiForSemiIn(500L).getId()).isEqualTo(700L);
    }

    @Test
    void persistsDraftThenSignsAsCurrentUserWithDispositionAudit() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, null);
        dto.getItems().get(0).setPassFlag(-1);
        dto.getItems().get(0).setResultValue(null);
        service.saveProcessRecord(id, dto);
        assertThat(heads.get(id).getStatus()).isEqualTo("DRAFT");
        assertThat(heads.get(id).getCheckTime()).isNull();
        verify(links, never()).insert(any(MesBatchLink.class));
        dto = record(id, "PASS");
        dto.setSampleQty(new BigDecimal("12"));
        dto.setQualifiedQty(new BigDecimal("12"));
        dto.setBatchNo("REAL-BATCH");
        dto.setPhotoUrls("[\"https://example.test/photo.jpg\"]");
        service.saveProcessRecord(id, dto);
        MesQualityInspection signed = heads.get(id);
        assertThat(signed.getStatus()).isEqualTo("CHECKED");
        assertThat(signed.getResult()).isEqualTo("PASS");
        assertThat(signed.getSampleQty()).isEqualByComparingTo("12");
        assertThat(signed.getBatchNo()).isEqualTo("REAL-BATCH");
        assertThat(signed.getPhotoUrls()).contains("photo.jpg");
        assertThat(signed.getInspector()).isEqualTo(77L);
        assertThat(signed.getReviewer()).isEqualTo(77L);
        assertThat(signed.getReviewerName()).isEqualTo("test-user");
        assertThat(signed.getCheckTime()).isNotNull();
        verify(links).insert(any(MesBatchLink.class));
        ArgumentCaptor<SysOperLog> logs = ArgumentCaptor.forClass(SysOperLog.class);
        verify(audit, atLeastOnce()).insert(logs.capture());
        assertThat(logs.getAllValues()).anySatisfy(l -> {
            assertThat(l.getMethod()).contains("CHECK_INSPECTION");
            assertThat(l.getUserId()).isEqualTo(77L);
        });
    }

    @Test
    void checkedRecordCannotBeEditedOrSignedAgain() {
        Long id = signFai();
        ProcessInspectionRecordDTO dto = record(id, null);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("不可修改");
        assertThatThrownBy(() -> service.check(id, "FAIL", "FROZEN", "改判")).hasMessageContaining("不可修改");
        assertThat(heads.get(id).getResult()).isEqualTo("PASS");
    }

    @Test
    void validatesProcessOwnershipAgainWhenSaving() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        heads.get(id).setProductId(99L);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("不匹配");
        heads.get(id).setProductId(20L);
        heads.get(id).setSourceBillNo("WRONG-ORDER");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("不匹配");
        heads.get(id).setSourceBillNo(order.getBillNo());
        order.setBillStatus("DRAFT");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("尚未开工");
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "-1,0,0", "5,-1,6", "5,6,-1", "5,4,0", "5.00001,5.00001,0", "100000000000000,100000000000000,0"})
    void rejectsInvalidQuantitiesEvenInDraft(String sample, String good, String bad) {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, null);
        dto.setSampleQty(new BigDecimal(sample));
        dto.setQualifiedQty(new BigDecimal(good));
        dto.setUnqualifiedQty(new BigDecimal(bad));
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).isInstanceOf(BizException.class);
        verify(itemService, never()).batchSave(anyLong(), anyList());
    }

    @Test
    void draftAllowsMissingQuantitiesButSigningDoesNot() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, null);
        dto.setSampleQty(null);
        dto.setQualifiedQty(null);
        dto.setUnqualifiedQty(null);
        service.saveProcessRecord(id, dto);
        dto.setResult("PASS");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("签核必须填写");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASS_DISPOSITION", "HOLD", "FAIL_REJECT", "", "OTHER"})
    void rejectsAlternateResultsThatCouldBypassChecks(String result) {
        Long id = ensure("FAI").getId();
        assertThatThrownBy(() -> service.saveProcessRecord(id, record(id, result))).hasMessageContaining("PASS/FAIL");
        measuredInStore(id);
        assertThatThrownBy(() -> service.check(id, result, "NONE", "")).isInstanceOf(BizException.class);
    }

    @Test
    void failRequiresEvidenceAndFreezesBatchWithExplicitReason() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, "FAIL");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("至少有一项");
        dto.getItems().get(0).setPassFlag(0);
        dto.getItems().get(0).setResultValue("半成品卷批次不一致");
        MesBatch batch = new MesBatch();
        batch.setId(30L);
        batch.setBatchNo(order.getBatchNo());
        when(batches.selectOne(any())).thenReturn(batch);
        service.saveProcessRecord(id, dto);
        assertThat(heads.get(id).getResult()).isEqualTo("FAIL");
        assertThat(heads.get(id).getDispositionReason()).contains("BAG_MATERIAL", "不合格");
        assertThat(batch.getStatus()).isEqualTo("FROZEN");
        assertThat(batch.getFrozenInspectionId()).isEqualTo(id);
        verify(batches).updateById(batch);
        assertThat(service.hasPassedFaiForProcess(100L)).isFalse();
    }

    @Test
    void nonzeroBadQuantityRejectsPassAndSupportsFail() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.setQualifiedQty(new BigDecimal("4"));
        dto.setUnqualifiedQty(BigDecimal.ONE);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("不能签核 PASS");
        dto.setResult("FAIL");
        service.saveProcessRecord(id, dto);
        assertThat(heads.get(id).getDispositionReason()).contains("不良数量: 1");
    }

    @Test
    void rejectsOversizedFieldsAndInvalidPhotoJsonBeforeWriting() {
        Long id = ensure("FAI").getId();
        ProcessInspectionRecordDTO dto = record(id, null);
        dto.getItems().get(0).setResultValue("测".repeat(129));
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("128");
        dto.getItems().get(0).setResultValue("5");
        dto.getItems().get(0).setJudgmentBasis("依".repeat(256));
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("255");
        dto.getItems().get(0).setJudgmentBasis("工艺卡");
        dto.setBatchNo("b".repeat(65));
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("64");
        dto.setBatchNo("batch");
        dto.setPhotoUrls("not-json");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("JSON 数组");
        dto.setPhotoUrls("[123]");
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("JSON 数组");
        verify(itemService, never()).batchSave(anyLong(), anyList());
    }

    @Test
    void genericCreateCannotTrustStatusAndCannotCreateForgedProcessRecord() {
        InspectionCreateDTO dto = new InspectionCreateDTO();
        MesQualityInspection head = new MesQualityInspection();
        head.setProductId(20L);
        head.setInspectionType("IQC");
        head.setStatus("CHECKED");
        head.setResult("PASS");
        head.setReviewer(1L);
        dto.setHead(head);
        Long id = service.create(dto, "forged-user");
        assertThat(heads.get(id).getStatus()).isEqualTo("DRAFT");
        assertThat(heads.get(id).getResult()).isEqualTo("HOLD");
        assertThat(heads.get(id).getReviewer()).isNull();
        head.setSourceBillType("PRD_PROCESS");
        head.setInspectionType("FAI");
        assertThatThrownBy(() -> service.create(dto, "forged-user")).hasMessageContaining("ensure-for-process");
    }

    @Test
    void newSemiFaiIsUntestedAndCannotSignWithoutItemsOrActualMeasurements() {
        PrdSemiIn semi = new PrdSemiIn();
        semi.setId(500L);
        semi.setProductId(20L);
        semi.setBillNo("SEMI-500");
        semi.setQty(BigDecimal.TEN);
        Long id = service.createOnSemiIn(semi);
        assertThat(rows.get(id)).hasSize(8).allSatisfy(it -> assertThat(it.getPassFlag()).isEqualTo(1));
        assertThatThrownBy(() -> service.signPassedFaiForSemiIn(500L, id, null, BigDecimal.TEN, "假签名", null))
                .hasMessageContaining("未完成实测");
        rows.get(id).clear();
        assertThatThrownBy(() -> service.signPassedFaiForSemiIn(500L, id, null, BigDecimal.TEN, "假签名", null))
                .hasMessageContaining("无模板项");
        verify(items, times(8)).insert(any(QmsIqcItem.class));
    }

    @Test
    void historicalSignedAndDraftItemsAreNotReseededOrDeleted() {
        Long id = ensure("FAI").getId();
        rows.get(id).get(0).setRequirement("历史要求");
        rows.get(id).remove(1);
        ensure("FAI");
        assertThat(rows.get(id)).hasSize(12);
        assertThat(rows.get(id).get(0).getRequirement()).isEqualTo("历史要求");
        heads.get(id).setStatus("CHECKED");
        rows.get(id).clear();
        ensure("FAI");
        assertThat(rows.get(id)).isEmpty();
        verify(items, times(13)).insert(any(QmsIqcItem.class));
        verify(items, never()).deleteByIqcId(anyLong());
    }

    @Test
    void bagReportUsesBagPatAndReusesDraftOnlyAfterBagFaiPasses() {
        assertThatThrownBy(() -> service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ZERO))
                .hasMessageContaining("本工序首检");
        signFai();
        Long id = service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ZERO);
        assertThat(rows.get(id)).hasSize(11);
        assertThat(rows.get(id)).filteredOn(item -> !Integer.valueOf(1).equals(item.getIsFoodGradeItem()))
                .allSatisfy(item -> assertThat(item.getItemCode()).startsWith("BAG_PAT_"));
        assertThat(rows.get(id)).filteredOn(item -> Integer.valueOf(1).equals(item.getIsFoodGradeItem()))
                .extracting(QmsIqcItem::getItemCode).containsExactlyInAnyOrder("BAG_FOOD_HYGIENE", "BAG_FOOD_REPORT", "BAG_FOOD_TRACE");
        assertThat(service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ONE)).isEqualTo(id);
        assertThat(heads.get(id).getSampleQty()).isNull();
    }

    @Test
    void blowFoodFaiUsesShortTemplateAndStillRequiresActualMeasurements() {
        bag.setProcessName("吹膜");
        product.setIsFoodGrade(1);
        Long id = ensure("FAI").getId();
        assertThat(rows.get(id)).extracting(QmsIqcItem::getItemCode).contains("FOOD_COMPLIANCE_DOC", "FOOD_HYGIENE");
        ensure("FAI");
        assertThat(rows.get(id)).hasSize(8);
        ProcessInspectionRecordDTO dto = record(id, "PASS");
        dto.getItems().get(7).setResultValue(null);
        assertThatThrownBy(() -> service.saveProcessRecord(id, dto)).hasMessageContaining("未完成实测");
        service.saveProcessRecord(id, record(id, "PASS"));
        assertThat(heads.get(id).getStatus()).isEqualTo("CHECKED");
        verify(items, times(8)).insert(any(QmsIqcItem.class));
        verify(items, never()).deleteByIqcId(anyLong());
    }

    @Test
    void staleBlowFaiDraftWithRemovedItemsCannotBeSigned() {
        bag.setProcessName("吹膜");
        product.setIsFoodGrade(1);
        Long id = ensure("FAI").getId();
        rows.get(id).add(row(id, 9, "BLOW_ELONGATION"));
        rows.get(id).add(row(id, 10, "EVAP_RESIDUE"));
        assertThatThrownBy(() -> service.saveProcessRecord(id, record(id, "PASS")))
                .hasMessageContaining("代码集合不完整");
        verify(inspections, never()).updateById(any(MesQualityInspection.class));
        assertThat(heads.get(id).getStatus()).isEqualTo("DRAFT");
    }

    // ==================================================================
    // v1.1.148 首检/巡检按工序阶段拆「吹膜 / 制袋」四类:
    //   检验单表只存 FAI/PAT + 源工序 id, 阶段是派生的, 列表过滤与详情都要能分开
    // ==================================================================

    private AbstractWrapper<?, ?, ?> lastPageWrapper;

    @Test
    void pageNarrowsBlowToBlowProcessesAndLegacySemiInFai() {
        stubPage();
        service.page(1, 20, "FAI", null, null, "BLOW");
        String sql = lastPageWrapper.getSqlSegment();
        assertThat(sql).contains("IN (SELECT id FROM prd_process WHERE deleted = 0")
                .contains("process_name LIKE '%吹膜%'")
                .contains("process_name NOT LIKE '%制袋%'");
        assertThat(lastPageWrapper.getParamNameValuePairs().values())
                .contains("PRD_PROCESS", "PRD_SEMI_IN", "FAI");
    }

    @Test
    void pageNarrowsBagToBagProcessesOnly() {
        stubPage();
        service.page(1, 20, "PAT", null, null, "bag");
        String sql = lastPageWrapper.getSqlSegment();
        assertThat(sql).contains("process_name LIKE '%制袋%'").doesNotContain("吹膜");
        assertThat(lastPageWrapper.getParamNameValuePairs().values()).contains("PRD_PROCESS", "PAT");
    }

    @Test
    void pageWithoutStageKeepsSingleConditionAndIgnoresUnknownStage() {
        stubPage();
        service.page(1, 20, null, null, null, "分切");
        assertThat(lastPageWrapper.getSqlSegment()).doesNotContain("prd_process");
        service.page(1, 20, null, null, null, null);
        assertThat(lastPageWrapper.getSqlSegment()).doesNotContain("prd_process");
    }

    @Test
    void pageStampsStageLabelAndProcessNameForEachRow() {
        bag.setProcessName("制袋一工序");
        process(101L, "吹膜");
        MesQualityInspection bagRow = headRow(801L, "PRD_PROCESS", 100L, "FAI");
        MesQualityInspection blowRow = headRow(802L, "PRD_PROCESS", 101L, "PAT");
        MesQualityInspection legacySemi = headRow(803L, "PRD_SEMI_IN", 500L, "FAI");
        MesQualityInspection unknown = headRow(804L, "PRD_PROCESS", 999L, "PAT");
        stubPage(bagRow, blowRow, legacySemi, unknown);

        IPage<MesQualityInspection> page = service.page(1, 20, null, null, null, null);

        assertThat(page.getRecords()).extracting(MesQualityInspection::getStage)
                .containsExactly("BAG", "BLOW", "BLOW", null);
        assertThat(page.getRecords()).extracting(MesQualityInspection::getStageLabel)
                .containsExactly("制袋", "吹膜", "吹膜", null);
        assertThat(page.getRecords()).extracting(MesQualityInspection::getProcessName)
                .containsExactly("制袋一工序", "吹膜", null, null);
    }

    @Test
    void detailReturnsStageSoClientCanLabelRecord() {
        bag.setProcessName("吹膜");
        Long id = ensure("FAI").getId();
        Map<String, Object> detail = service.detail(id);
        assertThat(detail).containsEntry("stage", "BLOW").containsEntry("stageLabel", "吹膜")
                .containsEntry("processName", "吹膜");
        assertThat(((MesQualityInspection) detail.get("head")).getStage()).isEqualTo("BLOW");
    }

    @Test
    void legacySemiInFaiDetailIsLabelledBlow() {
        MesQualityInspection semi = headRow(999L, "PRD_SEMI_IN", 500L, "FAI");
        heads.put(999L, semi);
        Map<String, Object> detail = service.detail(999L);
        assertThat(detail).containsEntry("stage", "BLOW").containsEntry("stageLabel", "吹膜");
    }

    /**
     * v1.1.153 打印口径: 首检/巡检也要逐项列出技术要求/实测值/单项判定,
     * 而 detail() 只对成品检 (OQC/OQC_RETURN) 回传 items —— 打印必须走 detailForPrint.
     */
    @Test
    void detailForPrintIncludesItemsForFaiWhileDetailDoesNot() {
        MesQualityInspection fai = ensure("FAI");
        Map<String, Object> detail = service.detailForPrint(fai.getId());

        assertThat(detail).containsKeys("head", "items", "defects", "attachments");
        assertThat((List<?>) detail.get("items")).isNotEmpty();
        assertThat(service.detail(fai.getId())).doesNotContainKey("items");

        assertThatThrownBy(() -> service.detailForPrint(999999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("检验单不存在");
    }

    /**
     * 订单版 IQC 单头只存第一个原料名, 但一张采购订单可能含多原料.
     * 打印报告必须列出关联订单的全部原料行, 不能只显示单头那一个.
     */
    @Test
    void detailForPrintReturnsAllOrderMaterialsForPurOrderIqc() {
        MesQualityInspection iqc = headRow(7001L, "PUR_ORDER", 555L, "IQC");
        iqc.setProductName("赛科0220kj");
        heads.put(7001L, iqc);
        PurOrderDetail m1 = new PurOrderDetail();
        m1.setProductName("赛科0220kj");
        m1.setSpec("S1");
        m1.setQty(new BigDecimal("1500"));
        m1.setUnitName("kg");
        PurOrderDetail m2 = new PurOrderDetail();
        m2.setProductName("扬巴ldpe2426h");
        m2.setSpec("S2");
        m2.setQty(new BigDecimal("1500"));
        m2.setUnitName("kg");
        when(purOrderDetails.selectByOrderId(555L)).thenReturn(List.of(m1, m2));

        Map<String, Object> detail = service.detailForPrint(7001L);

        assertThat(detail).containsKey("orderMaterials");
        List<?> materials = (List<?>) detail.get("orderMaterials");
        assertThat(materials).hasSize(2);
        assertThat(materials).extracting(o -> ((PurOrderDetail) o).getProductName())
                .containsExactly("赛科0220kj", "扬巴ldpe2426h");
    }

    /** 非订单源 (如 PUR_RECEIPT / PRD_ORDER) 不回传 orderMaterials, 避免误挂订单明细. */
    @Test
    void detailForPrintOmitsOrderMaterialsForNonPurOrderSource() {
        MesQualityInspection iqc = headRow(7002L, "PUR_RECEIPT", 666L, "IQC");
        heads.put(7002L, iqc);

        Map<String, Object> detail = service.detailForPrint(7002L);

        assertThat(detail).doesNotContainKey("orderMaterials");
        verify(purOrderDetails, never()).selectByOrderId(anyLong());
    }

    private MesQualityInspection headRow(Long id, String sourceBillType, Long sourceBillId, String type) {
        MesQualityInspection head = new MesQualityInspection();
        head.setId(id);
        head.setBillNo("QI-" + id);
        head.setSourceBillType(sourceBillType);
        head.setSourceBillId(sourceBillId);
        head.setInspectionType(type);
        head.setStatus("DRAFT");
        return head;
    }

    private void stubPage(MesQualityInspection... records) {
        doAnswer(inv -> {
            IPage<MesQualityInspection> page = inv.getArgument(0);
            lastPageWrapper = inv.getArgument(1);
            page.setRecords(new ArrayList<>(List.of(records)));
            return page;
        }).when(inspections).selectPage(any(), any());
    }

    private QmsIqcItem row(Long iqcId, int lineNo, String itemCode) {
        QmsIqcItem item = new QmsIqcItem();
        item.setId(sequence.incrementAndGet());
        item.setIqcId(iqcId);
        item.setLineNo(lineNo);
        item.setItemCode(itemCode);
        item.setItemName("历史项");
        item.setRequirement("历史要求");
        item.setSource("TEMPLATE");
        item.setPassFlag(-1);
        return item;
    }

    @Test
    void headUpdateConflictCannotTriggerApprovalOrDisposition() {
        Long id = ensure("FAI").getId();
        when(inspections.updateById(any(MesQualityInspection.class))).thenReturn(0);
        assertThatThrownBy(() -> service.saveProcessRecord(id, record(id, "PASS"))).hasMessageContaining("刷新");
        verify(links, never()).insert(any(MesBatchLink.class));
        assertThat(heads.get(id).getStatus()).isEqualTo("DRAFT");
    }

    // ==================================================================
    // v1.1.135 App「生产单详情 → 做成品检」: 按工单开 OQC + 归并 + 手机签核
    // ==================================================================

    @Test
    void ensureOqcForOrderSeedsBagTemplateFromProcessChainAndStaysIdempotent() {
        Map<String, Object> detail = service.ensureOqcForOrder(10L);
        MesQualityInspection head = (MesQualityInspection) detail.get("head");
        assertThat(detail).containsEntry("stage", "BAG").containsEntry("order", order);
        assertThat(head.getSourceBillType()).isEqualTo("PRD_ORDER");
        assertThat(head.getSourceBillId()).isEqualTo(10L);
        assertThat(head.getSourceBillNo()).isEqualTo("PRD-10");
        assertThat(head.getBatchNo()).isEqualTo("BATCH-10");
        assertThat(head.getStatus()).isEqualTo("DRAFT");
        List<QmsIqcItem> lines = rows.get(head.getId());
        assertThat(lines).hasSize(19).allSatisfy(it -> {
            assertThat(it.getItemCode()).startsWith(Integer.valueOf(1).equals(it.getIsFoodGradeItem()) ? "OQC_FOOD_" : "OQC_BAG_");
            assertThat(it.getPassFlag()).isEqualTo("OQC_BAG_PRINT".equals(it.getItemCode()) ? 2 : 1);
            assertThat(it.getSource()).isEqualTo("TEMPLATE");
        });
        assertThat(lines).extracting(QmsIqcItem::getItemCode)
                .contains("OQC_BAG_PRINT", "OQC_BAG_SEAL_STRENGTH");

        assertThat(((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId()).isEqualTo(head.getId());
        assertThat(rows.get(head.getId())).hasSize(19);
        verify(inspections, times(1)).insert(any(MesQualityInspection.class));
        verify(items, times(19)).insert(any(QmsIqcItem.class));
    }

    @Test
    void ensureOqcForOrderUsesFilmTemplateAndAppendsFoodItemsForFoodGradeBlowing() {
        bag.setProcessName("吹膜");
        product.setIsFoodGrade(1);
        Map<String, Object> detail = service.ensureOqcForOrder(10L);
        assertThat(detail).containsEntry("stage", "BLOW");
        assertThat((List<QmsIqcItem>) detail.get("items")).hasSize(19)
                .extracting(QmsIqcItem::getItemCode)
                .contains("OQC_FILM_ELONGATION", "OQC_FOOD_BATCH_RULE")
                .doesNotContain("OQC_BAG_PRINT");
        assertThat(rows.get(((MesQualityInspection) detail.get("head")).getId())
                .stream().filter(it -> it.getIsFoodGradeItem() == 1).count()).isEqualTo(6);
    }

    /** 存量工单没展开工序链时判不出膜/袋, 按膜卷模板兜底, 不能开出一张空检验单. */
    @Test
    void orderWithoutExpandedProcessesFallsBackToFilmTemplate() {
        processRows.clear();
        Map<String, Object> detail = service.ensureOqcForOrder(10L);
        assertThat(detail.get("stage")).isNull();
        assertThat((List<QmsIqcItem>) detail.get("items")).extracting(QmsIqcItem::getItemCode)
                .contains("OQC_FILM_ELONGATION").doesNotContain("OQC_BAG_PRINT");
    }

    /** 成品入库单已自动建过 OQC 时, 工单侧入口必须复用同一张, 一张工单只留一条出厂检验记录. */
    @Test
    void ensureOqcForOrderReusesTheHeadCreatedByFinishedIn() {
        Long existing = service.createOnFinish(finishedIn(600L));
        Map<String, Object> detail = service.ensureOqcForOrder(10L);
        assertThat(((MesQualityInspection) detail.get("head")).getId()).isEqualTo(existing);
        assertThat((List<QmsIqcItem>) detail.get("items")).hasSize(19);
        verify(inspections, times(1)).insert(any(MesQualityInspection.class));
    }

    @Test
    void relinkMovesOnlyDraftOqcToFinishedIn() {
        Long id = ((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId();
        assertThat(service.relinkOqcToFinishedIn(finishedIn(600L))).isTrue();
        MesQualityInspection moved = heads.get(id);
        assertThat(moved.getSourceBillType()).isEqualTo("PRD_FINISHED_IN");
        assertThat(moved.getSourceBillId()).isEqualTo(600L);
        assertThat(moved.getSourceBillNo()).isEqualTo("PFI-600");
        assertThat(moved.getBatchNo()).isEqualTo("BATCH-10");
        assertThat(moved.getSampleQty()).isEqualByComparingTo("500");
        assertThat(((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId()).isEqualTo(id);
        verify(inspections, times(1)).insert(any(MesQualityInspection.class));

        heads.get(id).setStatus("CHECKED");
        assertThat(service.relinkOqcToFinishedIn(finishedIn(601L))).isTrue();
        assertThat(heads.get(id).getSourceBillId()).isEqualTo(600L);
        verify(inspections, times(1)).updateById(any(MesQualityInspection.class));
    }

    /** 成品入库 OQC 门禁口径: 手机上签合格的工单侧检验单, 归并后仍算放行依据. */
    @Test
    void passedOqcOnOrderHeadReleasesFinishedInGate() {
        Long id = ((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId();
        service.relinkOqcToFinishedIn(finishedIn(600L));
        assertThat(service.hasPassedOqcForOrder(10L)).isFalse();

        MesQualityInspection head = heads.get(id);
        head.setSampleQty(new BigDecimal("5"));
        head.setQualifiedQty(new BigDecimal("5"));
        head.setUnqualifiedQty(BigDecimal.ZERO);
        rows.get(id).forEach(it -> { it.setPassFlag(1); it.setResultValue("实测记录"); });
        service.check(id, "PASS", "NONE", "现场成品检合格", null, null, null, null, null,
                null, null, null, "BATCH-现场");

        assertThat(service.hasPassedOqcForOrder(10L)).isTrue();
        MesQualityInspection signed = heads.get(id);
        assertThat(signed.getBatchNo()).isEqualTo("BATCH-现场");
        assertThat(signed.getRetainNo()).isEqualTo("LY-" + signed.getBillNo());
        assertThat(signed.getRetainDueDate()).isEqualTo(java.time.LocalDate.now().plusMonths(24));
        assertThat(signed.getReviewerName()).isNotBlank();
        assertThat(signed.getApproverName()).isNotBlank();
    }

    /** 待判定 (HOLD) 允许留空实测, 但一旦判合格就必须逐项实测 — App 半填不能放行. */
    @Test
    void partiallyMeasuredOqcCannotBeSignedPassed() {
        Long id = ((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId();
        heads.get(id).setSampleQty(new BigDecimal("5"));
        heads.get(id).setQualifiedQty(new BigDecimal("5"));
        heads.get(id).setUnqualifiedQty(BigDecimal.ZERO);
        rows.get(id).get(0).setPassFlag(1);
        rows.get(id).get(0).setResultValue("实测记录");
        assertThatThrownBy(() -> service.check(id, "PASS", "NONE", "")).hasMessageContaining("未完成实测");
        assertThat(heads.get(id).getStatus()).isEqualTo("DRAFT");
        service.check(id, "HOLD", "NONE", "先记录部分实测");
        assertThat(heads.get(id).getStatus()).isEqualTo("CHECKED");
    }

    @Test
    void ensureOqcForOrderRejectsOrderWithoutProduct() {
        order.setProductId(null);
        assertThatThrownBy(() -> service.ensureOqcForOrder(10L)).hasMessageContaining("未关联产品");
        assertThatThrownBy(() -> service.ensureOqcForOrder(999L)).hasMessageContaining("生产单不存在");
        verify(inspections, never()).insert(any(MesQualityInspection.class));
    }

    // ==================================================================
    // v1.1.150 工业品 (is_food_grade != 1) 不产生首检/巡检/成品检记录
    // ==================================================================

    @ParameterizedTest
    @CsvSource({"制袋,FAI", "制袋,PAT", "吹膜,FAI", "吹膜,PAT"})
    void industrialProductCreatesNoProcessInspection(String name, String type) {
        bag.setProcessName(name);
        product.setIsFoodGrade(0);
        assertThatThrownBy(() -> service.ensureForProcess(10L, 100L, type))
                .hasMessageContaining("工业品");
        verify(inspections, never()).insert(any(MesQualityInspection.class));
        verify(items, never()).insert(any(QmsIqcItem.class));
    }

    @Test
    void industrialProductCreatesNoOqcOrAutoTriggeredInspection() {
        product.setIsFoodGrade(0);
        assertThatThrownBy(() -> service.ensureOqcForOrder(10L)).hasMessageContaining("工业品");
        assertThat(service.createOnFinish(finishedIn(600L))).isNull();
        PrdSemiIn semi = new PrdSemiIn();
        semi.setId(500L);
        semi.setProductId(20L);
        semi.setBillNo("SEMI-500");
        assertThat(service.createOnSemiIn(semi)).isNull();
        when(semiIns.selectById(500L)).thenReturn(semi);
        assertThatThrownBy(() -> service.ensureFaiForSemiIn(500L)).hasMessageContaining("工业品");
        bag.setProcessName("吹膜");
        assertThat(service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        verify(inspections, never()).insert(any(MesQualityInspection.class));
    }

    @Test
    void manualCreateRejectsIndustrialInspectionTypes() {
        product.setIsFoodGrade(0);
        InspectionCreateDTO dto = new InspectionCreateDTO();
        MesQualityInspection head = new MesQualityInspection();
        head.setProductId(20L);
        head.setSourceBillType("PRD_FINISHED_IN");
        head.setSourceBillId(600L);
        head.setInspectionType("OQC");
        dto.setHead(head);
        assertThatThrownBy(() -> service.create(dto, "tester")).hasMessageContaining("工业品");
        head.setInspectionType("PAT");
        assertThatThrownBy(() -> service.create(dto, "tester")).hasMessageContaining("工业品");
        verify(inspections, never()).insert(any(MesQualityInspection.class));
    }

    /** 口径只拦新建: 改成工业品后, 历史已建的检验单仍要能打开/续签 (用户要求保留可查). */
    @Test
    void existingInspectionStaysOpenableAfterProductBecomesIndustrial() {
        Long fai = signFai();
        product.setIsFoodGrade(0);
        assertThat(((MesQualityInspection) service.ensureForProcess(10L, 100L, "FAI").get("head")).getId())
                .isEqualTo(fai);
        assertThatThrownBy(() -> service.ensureForProcess(10L, 100L, "PAT")).hasMessageContaining("工业品");
    }

    private PrdFinishedIn finishedIn(Long id) {
        PrdFinishedIn finishedIn = new PrdFinishedIn();
        finishedIn.setId(id);
        finishedIn.setBillNo("PFI-" + id);
        finishedIn.setPrdOrderId(10L);
        finishedIn.setPrdOrderNo("PRD-10");
        finishedIn.setProductId(20L);
        finishedIn.setProductCode("P20");
        finishedIn.setProductName("真实生产产品");
        finishedIn.setSpec("订单规格");
        finishedIn.setBatchNo("BATCH-" + id);
        finishedIn.setQty(new BigDecimal("500"));
        finishedInRows.put(id, finishedIn);
        return finishedIn;
    }

    private PrdProcess process(Long id, String name) {
        PrdProcess process = new PrdProcess();
        process.setId(id);
        process.setPrdOrderId(10L);
        process.setPrdOrderNo("PRD-10");
        process.setFromDefId(id + 1000);
        process.setProcessName(name);
        process.setWorkshop("真实车间");
        process.setOperator("真实操作员");
        process.setStatus(0);
        processRows.put(id, process);
        return process;
    }

    private MesQualityInspection ensure(String type) {
        return (MesQualityInspection) service.ensureForProcess(10L, 100L, type).get("head");
    }

    private Long signFai() {
        Long id = ensure("FAI").getId();
        service.saveProcessRecord(id, record(id, "PASS"));
        return id;
    }

    private ProcessInspectionRecordDTO record(Long id, String result) {
        ProcessInspectionRecordDTO dto = new ProcessInspectionRecordDTO();
        dto.setSampleQty(new BigDecimal("5"));
        dto.setQualifiedQty(new BigDecimal("5"));
        dto.setUnqualifiedQty(BigDecimal.ZERO);
        dto.setResult(result);
        dto.setItems(rows.get(id).stream().map(row -> {
            QmsIqcItem input = new QmsIqcItem();
            input.setId(row.getId());
            input.setItemCode(row.getItemCode());
            input.setPassFlag(1);
            input.setResultValue("实测5.0；按检验规程核验符合");
            input.setJudgmentBasis("产品标准及工艺卡 V2");
            return input;
        }).collect(Collectors.toList()));
        return dto;
    }

    private void measuredInStore(Long id) {
        MesQualityInspection head = heads.get(id);
        head.setSampleQty(new BigDecimal("5"));
        head.setQualifiedQty(new BigDecimal("5"));
        head.setUnqualifiedQty(BigDecimal.ZERO);
        rows.get(id).forEach(it -> { it.setPassFlag(1); it.setResultValue("实测记录"); });
    }

    private MesQualityInspection copyHead(MesQualityInspection source) {
        if (source == null) return null;
        MesQualityInspection copy = new MesQualityInspection();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private QmsIqcItem copyItem(QmsIqcItem source) {
        QmsIqcItem copy = new QmsIqcItem();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    /**
     * v1.1.184 — 进货检验默认判定必须以模板第 6 列 (N/A / PASS) 为唯一真源:
     * 此前代码里另有一套 NA 集合, 与模板列不一致 (食品接触重金属: 模板写 PASS、代码写 N/A),
     * 且保存闸又不认 N/A, 导致 App「保存检验记录」必然失败。
     */
    @Test
    void iqcDefaultItemsFollowTemplateDefaultColumn() {
        MesQualityInspection ins = new MesQualityInspection();
        ins.setId(9101L);
        ins.setProductId(20L);
        service.loadDefaultIqcItems(ins);
        ArgumentCaptor<QmsIqcItem> cap = ArgumentCaptor.forClass(QmsIqcItem.class);
        verify(items, atLeastOnce()).insert(cap.capture());
        Map<String, Integer> flags = cap.getAllValues().stream()
                .collect(Collectors.toMap(QmsIqcItem::getItemCode, QmsIqcItem::getPassFlag, (a, b) -> a));
        assertThat(flags.get("MFR_DENSITY")).isEqualTo(2);
        assertThat(flags.get("MFR")).isEqualTo(2);
        assertThat(flags.get("DENSITY")).isEqualTo(2);
        assertThat(flags.get("FOOD_CONTACT_HEAVY_METAL")).isEqualTo(2);
        assertThat(flags.get("SENSORY")).isEqualTo(1);
        assertThat(flags.get("SUPPLIER_REPORT")).isEqualTo(1);
        // 单一真源: 模板第 6 列写什么, 默认判定就是什么 (今天 FOOD_CONTACT_HEAVY_METAL 模板写 PASS、代码写 N/A)
        for (String[] row : QmsInspectionTemplateItems.DEFAULT_IQC_ITEMS_COMMON) {
            assertThat(flags.get(row[0])).as("模板列与默认判定不一致: %s", row[0])
                    .isEqualTo("N/A".equals(row[5]) ? 2 : 1);
        }
        for (String[] row : QmsInspectionTemplateItems.DEFAULT_IQC_ITEMS_FOOD) {
            assertThat(flags.get(row[0])).as("模板列与默认判定不一致: %s", row[0])
                    .isEqualTo("N/A".equals(row[5]) ? 2 : 1);
        }
    }
}
