package com.industrial.erp.modules.qms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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
                mock(PurOrderMapper.class), mock(PurOrderDetailMapper.class), semiIns, finishedIns,
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
        product.setIsFoodGrade(0);
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
            Map<String, QmsIqcItem> byCode = QmsIqcItemService.validateTemplateSubmission(rows.get(id), submitted);
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
    @CsvSource({"制袋,FAI,0,10,BAG", "制袋,FAI,1,13,BAG", "制袋,PAT,0,8,BAG", "制袋,PAT,1,11,BAG",
            "吹膜,FAI,0,6,BLOW", "吹膜,FAI,1,8,BLOW", "吹膜,PAT,0,7,BLOW", "吹膜,PAT,1,11,BLOW"})
    void createsIndependentTemplatesWithUntestedItems(String name, String type, int food, int count, String stage) {
        bag.setProcessName(name);
        product.setIsFoodGrade(food);
        if (name.equals("制袋") && type.equals("PAT")) signFai();
        Map<String, Object> detail = service.ensureForProcess(10L, 100L, type);
        MesQualityInspection head = (MesQualityInspection) detail.get("head");
        assertThat(detail).containsEntry("stage", stage).containsEntry("order", order).containsEntry("process", bag);
        assertThat(rows.get(head.getId())).hasSize(count).allSatisfy(it -> {
            assertThat(it.getPassFlag()).isEqualTo(-1);
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
                .isEqualTo(food == 0 ? 0 : stage.equals("BAG") ? 3 : type.equals("FAI") ? 2 : 4);
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
        product.setIsFoodGrade(0);
        Long headId = ((MesQualityInspection) service.ensureForProcess(10L, 100L, "FAI").get("head")).getId();
        assertThat(rows.get(headId)).hasSize(6);
        rows.get(headId).clear();
        Map<String, Object> again = service.ensureForProcess(10L, 100L, "FAI");
        assertThat(((MesQualityInspection) again.get("head")).getId()).isEqualTo(headId);
        assertThat((List<QmsIqcItem>) again.get("items")).extracting(QmsIqcItem::getItemCode).containsExactlyInAnyOrder(
                "BLOW_MATERIAL_CHECK", "BLOW_APPEARANCE", "BLOW_THICKNESS", "BLOW_WIDTH",
                "BLOW_TENSILE", "BLOW_ODOR");
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
        assertThat(rows.get(next.getId())).allSatisfy(it -> assertThat(it.getPassFlag()).isEqualTo(-1));
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
        assertThat(rows.get(id)).hasSize(7).noneSatisfy(it -> assertThat(it.getItemCode()).startsWith("BAG_"));
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
        verify(items, times(10)).insert(any(QmsIqcItem.class));
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
        assertThat(rows.get(id)).hasSize(6).allSatisfy(it -> assertThat(it.getPassFlag()).isEqualTo(-1));
        assertThatThrownBy(() -> service.signPassedFaiForSemiIn(500L, id, null, BigDecimal.TEN, "假签名", null))
                .hasMessageContaining("未完成实测");
        rows.get(id).clear();
        assertThatThrownBy(() -> service.signPassedFaiForSemiIn(500L, id, null, BigDecimal.TEN, "假签名", null))
                .hasMessageContaining("无模板项");
        verify(items, times(6)).insert(any(QmsIqcItem.class));
    }

    @Test
    void historicalSignedAndDraftItemsAreNotReseededOrDeleted() {
        Long id = ensure("FAI").getId();
        rows.get(id).get(0).setRequirement("历史要求");
        rows.get(id).remove(1);
        ensure("FAI");
        assertThat(rows.get(id)).hasSize(9);
        assertThat(rows.get(id).get(0).getRequirement()).isEqualTo("历史要求");
        heads.get(id).setStatus("CHECKED");
        rows.get(id).clear();
        ensure("FAI");
        assertThat(rows.get(id)).isEmpty();
        verify(items, times(10)).insert(any(QmsIqcItem.class));
        verify(items, never()).deleteByIqcId(anyLong());
    }

    @Test
    void bagReportUsesBagPatAndReusesDraftOnlyAfterBagFaiPasses() {
        assertThatThrownBy(() -> service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ZERO))
                .hasMessageContaining("本工序首检");
        signFai();
        Long id = service.createOnReport(bag, order, BigDecimal.TEN, BigDecimal.ZERO);
        assertThat(rows.get(id)).hasSize(8).allSatisfy(item -> assertThat(item.getItemCode()).startsWith("BAG_PAT_"));
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
        product.setIsFoodGrade(0);
        Map<String, Object> detail = service.ensureOqcForOrder(10L);
        MesQualityInspection head = (MesQualityInspection) detail.get("head");
        assertThat(detail).containsEntry("stage", "BAG").containsEntry("order", order);
        assertThat(head.getSourceBillType()).isEqualTo("PRD_ORDER");
        assertThat(head.getSourceBillId()).isEqualTo(10L);
        assertThat(head.getSourceBillNo()).isEqualTo("PRD-10");
        assertThat(head.getBatchNo()).isEqualTo("BATCH-10");
        assertThat(head.getStatus()).isEqualTo("DRAFT");
        List<QmsIqcItem> lines = rows.get(head.getId());
        assertThat(lines).hasSize(13).allSatisfy(it -> {
            assertThat(it.getItemCode()).startsWith("OQC_BAG_");
            assertThat(it.getPassFlag()).isEqualTo(-1);
            assertThat(it.getSource()).isEqualTo("TEMPLATE");
        });
        assertThat(lines).extracting(QmsIqcItem::getItemCode)
                .contains("OQC_BAG_PRINT", "OQC_BAG_SEAL_STRENGTH");

        assertThat(((MesQualityInspection) service.ensureOqcForOrder(10L).get("head")).getId()).isEqualTo(head.getId());
        assertThat(rows.get(head.getId())).hasSize(13);
        verify(inspections, times(1)).insert(any(MesQualityInspection.class));
        verify(items, times(13)).insert(any(QmsIqcItem.class));
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
        assertThat((List<QmsIqcItem>) detail.get("items")).hasSize(13);
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
}
