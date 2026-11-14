package com.industrial.erp.modules.qms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.entity.MesBatchLink;
import com.industrial.erp.modules.mes.mapper.MesBatchLinkMapper;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.PrdProcessMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.qms.QmsItemCodes;
import com.industrial.erp.modules.qms.dto.ProcessInspectionRecordDTO;
import com.industrial.erp.modules.purchase.entity.PurInboundPlan;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.qms.dto.InspectionCreateDTO;
import com.industrial.erp.modules.qms.entity.MesQualityDefect;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import com.industrial.erp.modules.qms.mapper.MesQualityDefectMapper;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.qms.mapper.QmsIqcItemMapper;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.BAG_FAI_ITEMS;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.BAG_FOOD_ITEMS;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.BAG_PAT_ITEMS;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.DEFAULT_FAI_ITEMS_COMMON;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.DEFAULT_FAI_ITEMS_FOOD;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.DEFAULT_IQC_ITEMS_COMMON;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.DEFAULT_IQC_ITEMS_FOOD;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.DEFAULT_PAT_ITEMS_COMMON;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.DEFAULT_PROCESS_ITEMS_FOOD;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.OQC_BAG_ITEMS;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.OQC_FILM_ITEMS;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.OQC_FOOD_ITEMS;
import static com.industrial.erp.modules.qms.service.QmsInspectionTemplateItems.OQC_RETURN_ITEMS;

/**
 * v1.1.90 QMS 第一阶段 — 质量检验单 (IQC / FAI / PAT / OQC / OQC_RETURN) 主服务.
 *
 * <p>职责:
 * <ol>
 *   <li><b>手工创建/审核</b> — {@link #create} PC 端编辑器提交 head+defects;
 *       {@link #check} / {@link #reject} 写 result + disposition + 联动 mes_batch 锁/放行.</li>
 *   <li><b>自动触发器</b> — {@link #createOnReceipt} (IQC) / {@link #createOnFinish} (OQC) /
 *       {@link #createOnReturn} (OQC_RETURN) 由上游 service 在 {@code .check()} 末 try/catch
 *       调用, 失败仅记日志不阻塞业务. QMS 数据写入走同一事务 (上游 rollback → QMS 也回滚).</li>
 *   <li><b>批次联动</b> — FAIL+FROZEN / FAIL_REJECT 触发 {@code mes_batch.status=FROZEN/SCRAP}
 *       + {@code frozen_inspection_id=this.id}; PASS 写 {@code mes_batch_link(billType='DISPOSITION')}.</li>
 * </ol>
 *
 * <p>审计: 沿用 {@code McpAuditService} 教训 — <b>同步直写</b> {@code SysOperLogMapper.insert}
 * (高频 createOnReceipt 触发器也走同步, 不走 @Async). 主流程 try/catch 兜底, 审计失败不阻塞.
 */
@Service
public class QmsInspectionService {

    private static final Logger log = LoggerFactory.getLogger(QmsInspectionService.class);

    /** v1.1.150 — 只对食品级商品产生的检验类型 (首检/巡检/成品检). IQC 与退货检不受此限. */
    private static final Set<String> FOOD_GRADE_ONLY_TYPES = Set.of(
            Constants.INSPECTION_FAI, Constants.INSPECTION_PAT, Constants.INSPECTION_OQC);

    private final MesQualityInspectionMapper inspectionMapper;
    private final MesQualityDefectMapper defectMapper;
    private final MesBatchMapper batchMapper;
    private final MesBatchLinkMapper linkMapper;
    private final SysOperLogMapper operLogMapper;
    private final BillNoGenerator billNoGenerator;
    /** v1.1.90 触发器单 (IQC) 需从 receipt_detail 拉 batch_no, 注入 service */
    private final PurReceiptDetailMapper purReceiptDetailMapper;
    private final com.industrial.erp.modules.purchase.mapper.PurReceiptMapper receiptMapper;
    /** v1.1.101 XK — IQC 检验项 mapper (App 端录入/编辑/校验用) */
    private final QmsIqcItemMapper iqcItemMapper;
    /** v1.1.101 XK — IQC 附件/照片 mapper (detailForPlan 用) */
    private final com.industrial.erp.modules.qms.mapper.QmsIqcAttachmentMapper iqcAttachmentMapper;
    /** v1.1.101 XK — 商品表 (查 is_food_grade 决定加载哪些模板检验项) */
    private final BaseProductMapper productMapper;
    /** v1.1.101 XK — 采购计划 mapper (createFromPlan / hasPassedIqcForPlan 用) */
    private final com.industrial.erp.modules.purchase.mapper.PurInboundPlanMapper inboundPlanMapper;
    /** v1.1.101 订单为源 — 采购订单 mapper (createFromOrder / signPassedIqcForOrder / hasPassedIqcForOrder 用) */
    private final com.industrial.erp.modules.purchase.mapper.PurOrderMapper orderMapper;
    private final com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper orderDetailMapper;
    /** v1.1.101 App XK 生产全流程 — 半成品入库 mapper (signPassedFaiForSemiIn 写回门禁快照用) */
    private final com.industrial.erp.modules.production.mapper.PrdSemiInMapper prdSemiInMapper;
    /** v1.1.101 App XK 生产全流程 — 成品入库 mapper (signPassedOqcForFinishedIn 兜底建 OQC 用) */
    private final com.industrial.erp.modules.production.mapper.PrdFinishedInMapper prdFinishedInMapper;
    private final PrdOrderMapper prdOrderMapper;
    private final PrdProcessMapper prdProcessMapper;
    private final QmsIqcItemService iqcItemService;

    /** v1.1.158 — 检验报告号自动生成 (BG-YL-YYYYMMDD-NNN). 字段注入避免改动大构造函数签名 (单测直构). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.industrial.erp.modules.purchase.bill.ReportNoGenerator reportNoGenerator;

    public QmsInspectionService(MesQualityInspectionMapper inspectionMapper,
                                 MesQualityDefectMapper defectMapper,
                                 MesBatchMapper batchMapper,
                                 MesBatchLinkMapper linkMapper,
                                 SysOperLogMapper operLogMapper,
                                 BillNoGenerator billNoGenerator,
                                 PurReceiptDetailMapper purReceiptDetailMapper,
                                 com.industrial.erp.modules.purchase.mapper.PurReceiptMapper receiptMapper,
                                 QmsIqcItemMapper iqcItemMapper,
                                 com.industrial.erp.modules.qms.mapper.QmsIqcAttachmentMapper iqcAttachmentMapper,
                                 BaseProductMapper productMapper,
                                 @Lazy com.industrial.erp.modules.purchase.mapper.PurInboundPlanMapper inboundPlanMapper,
                                 @Lazy com.industrial.erp.modules.purchase.mapper.PurOrderMapper orderMapper,
                                 @Lazy com.industrial.erp.modules.purchase.mapper.PurOrderDetailMapper orderDetailMapper,
                                 @Lazy com.industrial.erp.modules.production.mapper.PrdSemiInMapper prdSemiInMapper,
                                 @Lazy com.industrial.erp.modules.production.mapper.PrdFinishedInMapper prdFinishedInMapper,
                                 PrdOrderMapper prdOrderMapper, PrdProcessMapper prdProcessMapper,
                                 QmsIqcItemService iqcItemService) {
        this.inspectionMapper = inspectionMapper;
        this.defectMapper = defectMapper;
        this.batchMapper = batchMapper;
        this.linkMapper = linkMapper;
        this.operLogMapper = operLogMapper;
        this.billNoGenerator = billNoGenerator;
        this.purReceiptDetailMapper = purReceiptDetailMapper;
        this.receiptMapper = receiptMapper;
        this.iqcItemMapper = iqcItemMapper;
        this.iqcAttachmentMapper = iqcAttachmentMapper;
        this.productMapper = productMapper;
        this.inboundPlanMapper = inboundPlanMapper;
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.prdSemiInMapper = prdSemiInMapper;
        this.prdFinishedInMapper = prdFinishedInMapper;
        this.prdOrderMapper = prdOrderMapper;
        this.prdProcessMapper = prdProcessMapper;
        this.iqcItemService = iqcItemService;
    }

    // ==================================================================
    // 1. 手工创建 (PC 端编辑器)
    // ==================================================================

    /**
     * 创建检验单 (DRAFT) + 缺陷行. 由 PC 端编辑器一次性 POST.
     *
     * <p>设置字段 (服务端兜底):
     * <ul>
     *   <li>{@code billNo} — BillNoGenerator.generate(BILL_QI) (e.g. QI202610100001)</li>
     *   <li>{@code billDate} — null 时填今天</li>
     *   <li>{@code inspector} — 当前登录用户 id</li>
     *   <li>{@code inspectorName} — 当前登录用户名</li>
     *   <li>{@code status} — DRAFT (强制, 不允许跳过审核直接 CHECKED)</li>
     *   <li>{@code result} — 默认 HOLD (待审核后改)</li>
     * </ul>
     *
     * @return 检验单 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(InspectionCreateDTO dto, String operator) {
        MesQualityInspection head = dto == null ? null : dto.getHead();
        if (head == null) throw BizException.of("检验单头不能为空");
        if (StrUtil.isBlank(head.getInspectionType())) throw BizException.of("检验类型必填");
        if ("PRD_PROCESS".equals(head.getSourceBillType())) {
            throw BizException.of("工序首检/巡检请通过 ensure-for-process 创建，产品和工单由服务器关联");
        }
        if (Constants.INSPECTION_FAI.equals(head.getInspectionType())
                && "PRD_SEMI_IN".equals(head.getSourceBillType())) {
            return ((MesQualityInspection) ensureFaiForSemiIn(head.getSourceBillId()).get("head")).getId();
        }
        if (head.getProductId() == null) throw BizException.of("产品必填");
        if (FOOD_GRADE_ONLY_TYPES.contains(head.getInspectionType())) {
            requireFoodGradeForInspection(head.getProductId());
        }
        head.setId(null);
        head.setDeleted(0);
        head.setVersion(0);
        head.setBillNo(billNoGenerator.generate(Constants.BILL_QI));
        if (head.getBillDate() == null) head.setBillDate(LocalDate.now());
        head.setInspector(SecurityContext.getUserId());
        head.setInspectorName(SecurityContext.getUsername());
        head.setStatus(Constants.STATUS_DRAFT);
        head.setResult(Constants.RESULT_HOLD);
        head.setCheckTime(null);
        head.setReviewer(null);
        head.setReviewerName(null);
        head.setApprover(null);
        head.setApproverName(null);
        head.setDisposition(null);
        head.setDispositionReason(null);
        head.setCreateBy(SecurityContext.getUserId());
        head.setCreateTime(LocalDateTime.now());
        head.setUpdateBy(SecurityContext.getUserId());
        head.setUpdateTime(LocalDateTime.now());
        inspectionMapper.insert(head);

        if (dto.getDefects() != null) {
            int line = 1;
            for (MesQualityDefect d : dto.getDefects()) {
                d.setInspectionId(head.getId());
                if (d.getLineNo() == null) d.setLineNo(line++);
                if (StrUtil.isBlank(d.getSeverity())) d.setSeverity("MINOR");
                defectMapper.insert(d);
            }
        }
        writeAudit("CREATE_INSPECTION", 1, "billNo=" + head.getBillNo()
                + " type=" + head.getInspectionType() + " product=" + head.getProductName(), null);
        log.info("[QMS] 创建检验单 billNo={} id={}", head.getBillNo(), head.getId());
        return head.getId();
    }

    // ==================================================================
    // 2. 审核 / 反审核 (PC 端)
    // ==================================================================

    /**
     * 审核检验单 — 写 result + disposition + 联动批次状态.
     *
     * <p>FAIL+FROZEN / FAIL_REJECT 触发 {@link #applyBatchDisposition}:
     * <ul>
     *   <li>FAIL+FROZEN: mes_batch.status=FROZEN, frozen_inspection_id=this.id</li>
     *   <li>FAIL_REJECT: mes_batch.status=SCRAP</li>
     *   <li>PASS: 仅写一条 mes_batch_link (billType='DISPOSITION') 留痕, 不锁批</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id, String result, String disposition, String reason) {
        check(id, result, disposition, reason, null, null, null, null, null);
    }

    /**
     * v1.1.100 OQC 3 签 + 留样: 审核放行时回填 审核人/批准人/留样编号/位置/期限.
     *
     * <p>仅 OQC 成品单 (inspectionType=OQC) 且 放行结果 (PASS / PASS_DISPOSITION) 回填 3 签;
     * 审核人/批准人 由前端传姓名, 缺则记当前登录用户; 留样编号缺则按 {@code LY-{billNo}} 生成,
     * 留样期限缺则 +24 月. 非 OQC 或 FAIL 路径不强制 (只留字段, 不强填).
     *
     * @param reviewerName / approverName 审核/批准人 姓名 (可空, 缺则当前用户)
     * @param retainNo / retainLoc / retainDueDate 留样编号/位置/期限 (可空, 缺则默认生成)
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id, String result, String disposition, String reason,
                      String reviewerName, String approverName,
                      String retainNo, String retainLoc, java.time.LocalDate retainDueDate) {
        check(id, result, disposition, reason, reviewerName, approverName, retainNo, retainLoc, retainDueDate,
                null, null, null, null);
    }

    /**
     * v1.1.131 成品检验签核重载: 允许在审核同一事务里落 抽检/合格/不良 数量 (可空, 空则沿用单据原值).
     *
     * <p>XK16-204 出厂检验记录须含检验数量与合格数量, PC 审核面板原先只能改 result/disposition,
     * 数量得回单另存, 容易漏填。数量仍走 {@link #validateQuantities} 一致性与上限校验。
     *
     * @param batchNo v1.1.135 App 现场成品检的批号 (可空; 实物批号常在末道工序才定, 允许签核前就地改)
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id, String result, String disposition, String reason,
                      String reviewerName, String approverName,
                      String retainNo, String retainLoc, java.time.LocalDate retainDueDate,
                      BigDecimal sampleQty, BigDecimal qualifiedQty, BigDecimal unqualifiedQty,
                      String batchNo) {
        if (StrUtil.isBlank(result)) throw BizException.of("检验结果必填");
        MesQualityInspection rec = lockInspectionDraft(id);
        if (isFinishedInspection(rec)) {
            if (sampleQty != null) rec.setSampleQty(sampleQty);
            if (qualifiedQty != null) rec.setQualifiedQty(qualifiedQty);
            if (unqualifiedQty != null) rec.setUnqualifiedQty(unqualifiedQty);
            if (StrUtil.isNotBlank(batchNo)) {
                QmsIqcItemService.requireLength(batchNo, 64, "批次号");
                rec.setBatchNo(batchNo.trim());
            }
        }
        if (QmsIqcItemService.isProcessInspection(rec)) {
            if ("PRD_PROCESS".equals(rec.getSourceBillType())) {
                PrdProcess process = requireProcess(rec.getSourceBillId(), false);
                PrdOrder order = requireActiveOrder(process.getPrdOrderId());
                validateProcessHead(rec, process, order);
                requirePatFai(process, rec.getInspectionType());
                validateQuantities(rec.getSampleQty(), rec.getQualifiedQty(), rec.getUnqualifiedQty(), true);
            }
            validateProcessResult(rec, iqcItemMapper.selectByIqcId(id), result);
            rec.setInspector(SecurityContext.getUserId());
            rec.setInspectorName(SecurityContext.getUsername());
            rec.setReviewer(SecurityContext.getUserId());
            rec.setReviewerName(SecurityContext.getUsername());
            if (Constants.RESULT_FAIL.equals(result) && StrUtil.isBlank(reason)) {
                reason = processFailureReason(rec, iqcItemMapper.selectByIqcId(id));
            }
        } else if (isFinishedInspection(rec)) {
            // v1.1.131 成品检验出厂放行门禁: 模板项必须逐项实测判定, 不允许空项或删项签核
            ensureOqcTemplateItems(rec);
            validateFinishedResult(rec, iqcItemMapper.selectByIqcId(id), result, reason);
            if (rec.getInspector() == null) rec.setInspector(SecurityContext.getUserId());
            if (StrUtil.isBlank(rec.getInspectorName())) rec.setInspectorName(SecurityContext.getUsername());
        }
        QmsIqcItemService.requireLength(reason, 255, "处置原因");
        rec.setResult(result);
        rec.setDisposition(StrUtil.isBlank(disposition) ? Constants.BATCH_FROZEN : disposition);
        rec.setDispositionReason(reason);
        rec.setCheckTime(LocalDateTime.now());
        rec.setStatus(Constants.STATUS_CHECKED);
        // v1.1.100 OQC 放行回填 3 签 + 留样
        if (Constants.INSPECTION_OQC.equals(rec.getInspectionType())
                && (Constants.RESULT_PASS.equals(result) || Constants.RESULT_PASS_DISPOSITION.equals(result))) {
            String curUser = SecurityContext.getUsername();
            rec.setReviewerName(StrUtil.isNotBlank(reviewerName) ? reviewerName : curUser);
            rec.setApproverName(StrUtil.isNotBlank(approverName) ? approverName : curUser);
            rec.setRetainNo(StrUtil.isNotBlank(retainNo) ? retainNo : ("LY-" + rec.getBillNo()));
            rec.setRetainLoc(retainLoc);
            rec.setRetainDueDate(retainDueDate != null ? retainDueDate : java.time.LocalDate.now().plusMonths(24));
        }
        if (inspectionMapper.updateById(rec) != 1) throw BizException.of("检验单已变化，请刷新后重试");

        applyBatchDisposition(rec);

        writeAudit("CHECK_INSPECTION", 1, "billNo=" + rec.getBillNo() + " result=" + result
                + " disposition=" + rec.getDisposition()
                + (Constants.INSPECTION_OQC.equals(rec.getInspectionType()) ? " oqc3sign=1 retainNo=" + rec.getRetainNo() : ""), null);
        log.info("[QMS] 审核检验单 billNo={} result={} disposition={} type={}",
                rec.getBillNo(), result, rec.getDisposition(), rec.getInspectionType());
    }

    /** 反向拒收 = FAIL_REJECT + SCRAP, 与 check 同效果但更明确语义. */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        check(id, Constants.RESULT_FAIL_REJECT, Constants.BATCH_SCRAP, reason);
    }

    /**
     * v1.1.174 超管将已审核检验单重置为草稿 (允许修改填错的数据).
     *
     * <p>逻辑:
     * <ol>
     *   <li>校验当前用户是超管 (controller 层 @SaCheckRole 已做, 这里双保险)</li>
     *   <li>锁定检验单, 确保状态是 CHECKED</li>
     *   <li>回滚批次联动: 如果关联了批次冻结/报废, 将批次状态改回 NORMAL, 删除 frozen_inspection_id</li>
     *   <li>删除 mes_batch_link 留痕记录</li>
     *   <li>清空审核字段: result/disposition/dispositionReason/checkTime/reviewer/approver/retainNo 等</li>
     *   <li>将 status 改回 DRAFT</li>
     *   <li>写审计日志</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetToDraft(Long id) {
        // 1) 双保险: 校验超管
        if (!SecurityContext.isSuperAdmin()) {
            throw BizException.of("仅超级管理员可重置检验单");
        }

        // 2) 锁定检验单 (SELECT FOR UPDATE)
        MesQualityInspection rec = inspectionMapper.selectByIdForUpdate(id);
        if (rec == null) throw BizException.of("检验单不存在: " + id);
        if (!Constants.STATUS_CHECKED.equals(rec.getStatus())) {
            throw BizException.of("只有已审核的检验单才能重置为草稿 (当前状态: " + rec.getStatus() + ")");
        }

        // 3) 回滚批次联动
        rollbackBatchDisposition(rec);

        // 4) 删除 mes_batch_link 留痕
        linkMapper.delete(new LambdaQueryWrapper<MesBatchLink>()
                .eq(MesBatchLink::getInspectionId, id));

        // 5) 清空审核字段
        rec.setResult(null);
        rec.setDisposition(null);
        rec.setDispositionReason(null);
        rec.setCheckTime(null);
        rec.setReviewer(null);
        rec.setReviewerName(null);
        rec.setApprover(null);
        rec.setApproverName(null);
        rec.setRetainNo(null);
        rec.setRetainLoc(null);
        rec.setRetainDueDate(null);
        rec.setSampleQty(null);
        rec.setQualifiedQty(null);
        rec.setUnqualifiedQty(null);

        // 6) 状态改回 DRAFT
        rec.setStatus(Constants.STATUS_DRAFT);

        // 7) 更新数据库
        if (inspectionMapper.updateById(rec) != 1) {
            throw BizException.of("检验单已变化，请刷新后重试");
        }

        // 8) 写审计日志
        writeAudit("RESET_TO_DRAFT", 1, "billNo=" + rec.getBillNo() + " id=" + id, null);
        log.info("[QMS] 超管重置检验单为草稿 billNo={} id={}", rec.getBillNo(), id);
    }

    /**
     * 回滚批次联动: 如果检验单关联了批次冻结/报废, 将批次状态改回 NORMAL.
     */
    private void rollbackBatchDisposition(MesQualityInspection ins) {
        if (StrUtil.isBlank(ins.getBatchNo())) return;

        MesBatch b = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, ins.getBatchNo())
                .eq(MesBatch::getDeleted, 0)
                .last("LIMIT 1"));
        if (b == null) return;

        // 只回滚本检验单冻结的批次 (frozen_inspection_id = this.id)
        if (Objects.equals(b.getFrozenInspectionId(), ins.getId())) {
            b.setStatus(Constants.BATCH_ACTIVE);
            b.setFrozenInspectionId(null);
            b.setHoldReason(null);
            batchMapper.updateById(b);
            log.info("[QMS] 回滚批次状态 batchNo={} inspectionId={}", b.getBatchNo(), ins.getId());
        }
    }

    /**
     * 批次联动: 锁批 / 放行 / 报废.
     *
     * <p>一期不联动 affected_batches 自动收集 (依赖源单据 detail 联动, 复杂, 留二期);
     * 仅按 head.batchNo 单条 batch 联动. 若 batchNo 为空, 只写 mes_batch_link 留痕, 不锁批.
     */
    private void applyBatchDisposition(MesQualityInspection ins) {
        // 1) 任何结果都写一条 mes_batch_link 留痕 (DISPOSITION 事件)
        MesBatchLink link = new MesBatchLink();
        link.setBillType("DISPOSITION");
        link.setBillNo(ins.getBillNo());
        link.setInspectionId(ins.getId());
        // childBatchNo / parentBatchNo — 一期不联动, 留空 (避免误导追溯链)
        link.setChildBatchNo(ins.getBatchNo() == null ? "" : ins.getBatchNo());
        linkMapper.insert(link);

        // 2) 仅 FAIL 路径才动 batch.status
        boolean isFail = Constants.RESULT_FAIL.equals(ins.getResult())
                || Constants.RESULT_FAIL_REJECT.equals(ins.getResult());
        if (!isFail || StrUtil.isBlank(ins.getBatchNo())) return;

        MesBatch b = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, ins.getBatchNo())
                .eq(MesBatch::getDeleted, 0)
                .last("LIMIT 1"));
        if (b == null) return;
        b.setStatus(Constants.RESULT_FAIL_REJECT.equals(ins.getResult())
                ? Constants.BATCH_SCRAP
                : Constants.BATCH_FROZEN);
        b.setFrozenInspectionId(ins.getId());
        b.setHoldReason("QMS " + ins.getResult() + ": "
                + (StrUtil.isBlank(ins.getDispositionReason()) ? "(无理由)" : ins.getDispositionReason()));
        batchMapper.updateById(b);
    }

    // ==================================================================
    // 3. 分页 + 详情 (查询)
    // ==================================================================

    /**
     * 检验单分页查询.
     *
     * @param pageNum   页码 (从 1 开始)
     * @param pageSize  每页条数 (BaseMapper 内 maxLimit=200 兜底)
     * @param inspectionType  筛选 (可空): IQC / FAI / PAT / OQC / OQC_RETURN
     * @param result           筛选 (可空): PASS / FAIL / HOLD / ...
     * @param status           筛选 (可空): DRAFT / CHECKED
     */
    @Transactional(readOnly = true)
    public IPage<MesQualityInspection> page(int pageNum, int pageSize,
                                             String inspectionType, String result, String status) {
        return page(pageNum, pageSize, inspectionType, result, status, null);
    }

    /**
     * 检验单分页查询 (v1.1.148 增工序阶段过滤).
     *
     * <p>首检/巡检要按「吹膜 / 制袋」拆成四类, 但检验单表只存 FAI/PAT + 源工序 id,
     * 阶段是派生的: {@code prd_process.process_name} 先认制袋再认吹膜, 与
     * {@link #processStage(PrdProcess)} 同口径; 早期挂在半成品入库单上的 FAI 视为吹膜。
     *
     * @param stage 筛选 (可空): BLOW=吹膜 / BAG=制袋, 其它值忽略
     */
    @Transactional(readOnly = true)
    public IPage<MesQualityInspection> page(int pageNum, int pageSize,
                                             String inspectionType, String result, String status,
                                             String stage) {
        Page<MesQualityInspection> p = new Page<>(pageNum, pageSize, true);
        LambdaQueryWrapper<MesQualityInspection> w = new LambdaQueryWrapper<>();
        w.eq(StrUtil.isNotBlank(inspectionType), MesQualityInspection::getInspectionType, inspectionType);
        w.eq(StrUtil.isNotBlank(result), MesQualityInspection::getResult, result);
        w.eq(StrUtil.isNotBlank(status), MesQualityInspection::getStatus, status);
        String st = StrUtil.trimToNull(stage);
        if ("BLOW".equalsIgnoreCase(st)) {
            // 工序名含吹膜 (且不含制袋) 的 PRD_PROCESS 单 + 旧口径的半成品入库 FAI
            w.and(q -> q.and(a -> a.eq(MesQualityInspection::getSourceBillType, "PRD_PROCESS")
                            .inSql(MesQualityInspection::getSourceBillId, BLOW_PROCESS_ID_SQL))
                    .or(b -> b.eq(MesQualityInspection::getSourceBillType, "PRD_SEMI_IN")
                            .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_FAI)));
        } else if ("BAG".equalsIgnoreCase(st)) {
            w.and(q -> q.eq(MesQualityInspection::getSourceBillType, "PRD_PROCESS")
                    .inSql(MesQualityInspection::getSourceBillId, BAG_PROCESS_ID_SQL));
        }
        w.orderByDesc(MesQualityInspection::getBillDate)
                .orderByDesc(MesQualityInspection::getId);
        IPage<MesQualityInspection> resultPage = inspectionMapper.selectPage(p, w);
        stampStage(resultPage.getRecords());
        return resultPage;
    }

    /** 阶段过滤子查询 (与 {@link #processStage(PrdProcess)} 同口径: 先认制袋, 再认吹膜) */
    private static final String BAG_PROCESS_ID_SQL =
            "SELECT id FROM prd_process WHERE deleted = 0 AND process_name LIKE '%制袋%'";
    private static final String BLOW_PROCESS_ID_SQL =
            "SELECT id FROM prd_process WHERE deleted = 0 AND process_name LIKE '%吹膜%' AND process_name NOT LIKE '%制袋%'";

    /**
     * 给检验单填派生阶段字段 (不入库): 列表/详情/打印/App 都靠它区分吹膜与制袋.
     * 工序已被软删或名字无法识别时 stage 为 null, 不阻断列表.
     */
    private void stampStage(List<MesQualityInspection> rows) {
        if (rows == null || rows.isEmpty()) return;
        Set<Long> processIds = rows.stream()
                .filter(r -> "PRD_PROCESS".equals(r.getSourceBillType()) && r.getSourceBillId() != null)
                .map(MesQualityInspection::getSourceBillId)
                .collect(Collectors.toSet());
        Map<Long, PrdProcess> processes = processIds.isEmpty() ? Map.of()
                : prdProcessMapper.selectBatchIds(processIds).stream()
                    .collect(Collectors.toMap(PrdProcess::getId, x -> x, (a, b) -> a));
        for (MesQualityInspection r : rows) {
            PrdProcess proc = r.getSourceBillId() == null ? null : processes.get(r.getSourceBillId());
            if (proc != null) {
                r.setProcessName(proc.getProcessName());
                r.setStage(processStage(proc));
            } else if ("PRD_SEMI_IN".equals(r.getSourceBillType())
                    && Constants.INSPECTION_FAI.equals(r.getInspectionType())) {
                r.setStage("BLOW");
            }
            r.setStageLabel(stageLabel(r.getStage()));
        }
    }

    private static String stageLabel(String stage) {
        if ("BLOW".equals(stage)) return "吹膜";
        if ("BAG".equals(stage)) return "制袋";
        return null;
    }

    /**
     * 检验单详情: head + defects 一起返回 (Map 结构, 便于前端展平).
     *
     * <p>v1.1.131 — 成品检验 (OQC/OQC_RETURN) 同时回传 items, PC 端逐项录入面板直接消费.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Map<String, Object> m = new HashMap<>();
        MesQualityInspection head = inspectionMapper.selectById(id);
        if (head == null) throw BizException.of("检验单不存在");
        stampStage(java.util.Collections.singletonList(head));
        m.put("head", head);
        m.put("stage", head.getStage());
        m.put("stageLabel", head.getStageLabel());
        m.put("processName", head.getProcessName());
        m.put("defects", defectMapper.selectByInspectionId(id));
        if (isFinishedInspection(head)) {
            m.put("items", iqcItemMapper.selectByIqcId(id));
        }
        return m;
    }

    /**
     * v1.1.153 XK 单据打印 (App): head + items + defects + attachments 一次取全.
     *
     * <p>与 {@link #detail} 的差异: 首检/巡检 (FAI/PAT) 也要检验项 — 打印记录必须逐项列出
     * 技术要求/实测值/单项判定, 而 detail 只对 OQC 回传 items. 不带鉴权, 由 controller 统一裁决.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detailForPrint(Long id) {
        MesQualityInspection head = inspectionMapper.selectById(id);
        if (head == null) throw BizException.of("检验单不存在");
        stampStage(java.util.Collections.singletonList(head));
        Map<String, Object> m = new HashMap<>();
        m.put("docType", "inspection");
        m.put("head", head);
        m.put("stage", head.getStage());
        m.put("stageLabel", head.getStageLabel());
        m.put("processName", head.getProcessName());
        m.put("items", iqcItemMapper.selectByIqcId(id));
        m.put("defects", defectMapper.selectByInspectionId(id));
        m.put("attachments", iqcAttachmentMapper.selectByIqcId(id));
        // 订单版 IQC 单头只存第一个原料名; 报告需列出关联采购订单的全部原料行
        if ("PUR_ORDER".equals(head.getSourceBillType()) && head.getSourceBillId() != null) {
            m.put("orderMaterials", orderDetailMapper.selectByOrderId(head.getSourceBillId()));
        }
        return m;
    }

    // ==================================================================
    // 4. 自动触发器 — 由上游 service .check() 末 try/catch 调用
    // ==================================================================

    /**
     * 采购入库审核通过 → 自动生成 IQC DRAFT 草稿.
     *
     * <p>由 {@code PurReceiptService.check()} 末 try/catch 调用, 失败仅记日志.
     * batchNo 取第一个有 batchNo 的 detail 行 (一期简化, 二期支持逐行 IQC).
     *
     * @return 生成的 inspection id, 或 null (上游无 detail / QMS 失败)
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOnReceipt(PurReceipt receipt) {
        if (receipt == null || receipt.getId() == null) return null;
        List<PurReceiptDetail> details = purReceiptDetailMapper.selectByReceiptId(receipt.getId());
        String batchNo = null;
        Long productId = null;
        String productCode = null;
        String productName = null;
        String spec = null;
        BigDecimal sampleQty = BigDecimal.ZERO;
        if (details != null) {
            for (PurReceiptDetail d : details) {
                if (d.getQty() != null) sampleQty = sampleQty.add(d.getQty());
                if (productId == null && d.getProductId() != null) {
                    productId = d.getProductId();
                    productCode = d.getProductCode();
                    productName = d.getProductName();
                    spec = d.getSpec();
                }
                if (StrUtil.isBlank(batchNo) && StrUtil.isNotBlank(d.getBatchNo())) {
                    batchNo = d.getBatchNo();
                }
            }
        }
        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_IQC);
        ins.setSourceBillType("PUR_RECEIPT");
        ins.setSourceBillId(receipt.getId());
        ins.setSourceBillNo(receipt.getBillNo());
        ins.setProductId(productId);
        ins.setProductCode(productCode);
        ins.setProductName(productName);
        ins.setSpec(spec);
        ins.setBatchNo(batchNo);
        ins.setSupplierId(receipt.getSupplierId());
        ins.setSampleQty(sampleQty);
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("采购入库 " + receipt.getBillNo() + " 自动生成 IQC 待检");
        return createInternal(ins);
    }

    /** 成品入库审核通过 → 自动生成 OQC DRAFT 草稿. */
    @Transactional(rollbackFor = Exception.class)
    public Long createOnFinish(PrdFinishedIn finishedIn) {
        if (finishedIn == null || finishedIn.getId() == null) return null;
        if (!isFoodGradeProduct(finishedIn.getProductId())) return null;   // 工业品不建出厂检验
        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_OQC);
        ins.setSourceBillType("PRD_FINISHED_IN");
        ins.setSourceBillId(finishedIn.getId());
        ins.setSourceBillNo(finishedIn.getBillNo());
        ins.setProductId(finishedIn.getProductId());
        ins.setProductCode(finishedIn.getProductCode());
        ins.setProductName(finishedIn.getProductName());
        ins.setSpec(finishedIn.getSpec());
        ins.setBatchNo(finishedIn.getBatchNo());
        ins.setWorkshop(finishedIn.getRemark());  // 一期简化: remark 当车间记录
        ins.setSampleQty(finishedIn.getQty());
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("成品入库 " + finishedIn.getBillNo() + " 自动生成 OQC 待检");
        Long id = createInternal(ins);
        ensureOqcTemplateItems(ins);
        return id;
    }

    /** 销售退货审核通过 → 自动生成 OQC_RETURN DRAFT 草稿. */
    @Transactional(rollbackFor = Exception.class)
    public Long createOnReturn(SalReturn salReturn) {
        if (salReturn == null || salReturn.getId() == null) return null;
        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_OQC_RETURN);
        ins.setSourceBillType("SAL_RETURN");
        ins.setSourceBillId(salReturn.getId());
        ins.setSourceBillNo(salReturn.getBillNo());
        ins.setCustomerId(salReturn.getCustomerId());
        ins.setBatchNo(null);  // 退货批次由 inspection 时手工填
        ins.setSampleQty(salReturn.getTotalQty());
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("销售退货 " + salReturn.getBillNo() + " 自动生成 OQC_RETURN 待检");
        Long id = createInternal(ins);
        ensureOqcTemplateItems(ins);
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long createOnReport(PrdProcess p, PrdOrder o, BigDecimal good, BigDecimal bad) {
        if (p == null || p.getId() == null) return null;
        PrdProcess process = prdProcessMapper.selectOne(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getId, p.getId()).eq(PrdProcess::getDeleted, 0).last("FOR UPDATE"));
        if (!isExpandedProcess(process) || processStage(process) == null) return null;
        Long orderId = o == null ? process.getPrdOrderId() : o.getId();
        if (!isFoodGradeOrder(orderId)) return null;   // 工业品不建巡检记录
        Map<String, Object> detail = ensureForProcess(orderId, process.getId(), Constants.INSPECTION_PAT);
        return ((MesQualityInspection) detail.get("head")).getId();
    }

    @Transactional(readOnly = true)
    public List<PrdProcess> listInspectionProcesses(Long orderId) {
        requireOrder(orderId);
        return prdProcessMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                        .eq(PrdProcess::getPrdOrderId, orderId)
                        .eq(PrdProcess::getDeleted, 0)
                        .isNotNull(PrdProcess::getFromDefId)
                        .orderByAsc(PrdProcess::getProcessNo).orderByAsc(PrdProcess::getId))
                .stream().filter(this::isExpandedProcess).filter(p -> processStage(p) != null)
                .collect(Collectors.toList());
    }

    public boolean isBagProcess(PrdProcess p) {
        return p != null && p.getProcessName() != null && p.getProcessName().contains("制袋");
    }

    private String processStage(PrdProcess process) {
        if (isBagProcess(process)) return "BAG";
        return process != null && process.getProcessName() != null && process.getProcessName().contains("吹膜")
                ? "BLOW" : null;
    }

    private boolean isExpandedProcess(PrdProcess process) {
        return process != null && process.getId() != null && process.getFromDefId() != null
                && Integer.valueOf(0).equals(process.getDeleted());
    }

    private PrdOrder requireOrder(Long orderId) {
        if (orderId == null) throw BizException.of("生产单 orderId 必填");
        PrdOrder order = prdOrderMapper.selectById(orderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleted())) throw BizException.of("生产单不存在");
        return order;
    }

    private PrdOrder requireActiveOrder(Long orderId) {
        PrdOrder order = requireOrder(orderId);
        if (!"RELEASED".equals(order.getBillStatus()) && !"PRODUCING".equals(order.getBillStatus())) {
            throw BizException.of("生产单尚未开工或已结束，仅 RELEASED/PRODUCING 可进行工序检验");
        }
        if (order.getProductId() == null) throw BizException.of("生产单未关联产品，不能开过程检验");
        return order;
    }

    private PrdProcess requireProcess(Long processId, boolean lock) {
        if (processId == null) throw BizException.of("工序 processId 必填");
        PrdProcess process = lock ? prdProcessMapper.selectOne(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getId, processId).eq(PrdProcess::getDeleted, 0).last("FOR UPDATE"))
                : prdProcessMapper.selectById(processId);
        if (!isExpandedProcess(process)) throw BizException.of("工序不存在或不是已启用展开的工序");
        if (processStage(process) == null) throw BizException.of("仅支持真实吹膜/制袋工序检验，无法识别当前工序");
        return process;
    }

    private MesQualityInspection latestForProcess(Long processId, String type, boolean lock) {
        if (processId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PRD_PROCESS")
                .eq(MesQualityInspection::getSourceBillId, processId)
                .eq(MesQualityInspection::getInspectionType, type)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId).last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    @Transactional(readOnly = true)
    public boolean hasPassedFaiForProcess(Long processId) {
        MesQualityInspection fai = latestForProcess(processId, Constants.INSPECTION_FAI, false);
        if (fai == null || !Constants.STATUS_CHECKED.equals(fai.getStatus())
                || !Constants.RESULT_PASS.equals(fai.getResult())
                || !"PRD_PROCESS".equals(fai.getSourceBillType())
                || !Objects.equals(processId, fai.getSourceBillId())
                || !Constants.INSPECTION_FAI.equals(fai.getInspectionType())) return false;
        PrdProcess process = prdProcessMapper.selectById(processId);
        if (!isExpandedProcess(process) || processStage(process) == null) return false;
        PrdOrder order = prdOrderMapper.selectById(process.getPrdOrderId());
        if (order == null || !Objects.equals(order.getBillNo(), fai.getSourceBillNo())
                || !Objects.equals(order.getProductId(), fai.getProductId())) return false;
        try {
            validateProcessResult(fai, iqcItemMapper.selectByIqcId(fai.getId()), Constants.RESULT_PASS);
            return true;
        } catch (BizException ex) {
            return false;
        }
    }

    private void requirePatFai(PrdProcess process, String type) {
        if (Constants.INSPECTION_PAT.equals(type) && isBagProcess(process) && !hasPassedFaiForProcess(process.getId())) {
            throw BizException.of("该制袋工序尚无已签核 PASS 的首件检验 FAI，请先完成本工序首检");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureForProcess(Long orderId, Long processId, String inspectionType) {
        if (!Constants.INSPECTION_FAI.equals(inspectionType) && !Constants.INSPECTION_PAT.equals(inspectionType)) {
            throw BizException.of("工序检验类型仅支持 FAI 或 PAT");
        }
        PrdProcess process = requireProcess(processId, true);
        PrdOrder order = requireActiveOrder(orderId);
        if (!Objects.equals(process.getPrdOrderId(), order.getId())) throw BizException.of("工序不属于所选生产单");
        MesQualityInspection head = latestForProcess(processId, inspectionType, true);
        if (head != null && (Constants.INSPECTION_FAI.equals(inspectionType) || Constants.STATUS_DRAFT.equals(head.getStatus()))) {
            // 存量单仍可打开/续签 (v1.1.150 只拦新建, 不冻结历史记录)
            validateProcessHead(head, process, order);
            // 老草稿明细被 sql/143 软删 (换企业标准模板) 后, App 只调本接口, 必须在这里按最新模板重灌;
            // ensureProcessTemplateItems 自带「非草稿/已有明细即返回」护栏, 重复调用无副作用。
            ensureProcessTemplateItems(head);
            return processDetail(head, process, order);
        }
        requireFoodGradeForInspection(order.getProductId());
        requirePatFai(process, inspectionType);
        head = new MesQualityInspection();
        head.setInspectionType(inspectionType);
        head.setSourceBillType("PRD_PROCESS");
        head.setSourceBillId(process.getId());
        head.setSourceBillNo(order.getBillNo());
        head.setProductId(order.getProductId());
        head.setProductCode(order.getProductCode());
        head.setProductName(order.getProductName());
        head.setSpec(order.getSpec());
        head.setBatchNo(order.getBatchNo());
        head.setWorkshop(process.getWorkshop());
        head.setBillDate(LocalDate.now());
        head.setStatus(Constants.STATUS_DRAFT);
        head.setResult(Constants.RESULT_HOLD);
        head.setInspector(SecurityContext.getUserId());
        head.setInspectorName(SecurityContext.getUsername());
        head.setRemark(StrUtil.maxLength("工序[" + process.getProcessName() + "] " + inspectionType
                + " 操作员: " + (StrUtil.isBlank(process.getOperator()) ? SecurityContext.getUsername() : process.getOperator()), 255));
        createInternal(head);
        ensureProcessTemplateItems(head);
        writeAudit("CREATE_PROCESS_INSPECTION", 1, "orderId=" + orderId + " processId=" + processId
                + " inspectionId=" + head.getId() + " type=" + inspectionType, null);
        return processDetail(head, process, order);
    }

    private Map<String, Object> processDetail(MesQualityInspection head, PrdProcess process, PrdOrder order) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("head", head);
        detail.put("items", iqcItemMapper.selectByIqcId(head.getId()));
        detail.put("process", process);
        detail.put("order", order);
        detail.put("stage", processStage(process));
        detail.put("stageLabel", stageLabel(processStage(process)));
        detail.put("processName", process.getProcessName());
        return detail;
    }

    private void validateProcessHead(MesQualityInspection head, PrdProcess process, PrdOrder order) {
        if (!Objects.equals(head.getSourceBillId(), process.getId())
                || !Objects.equals(process.getPrdOrderId(), order.getId())
                || !Objects.equals(head.getSourceBillNo(), order.getBillNo())
                || !Objects.equals(head.getProductId(), order.getProductId())) {
            throw BizException.of("检验单与真实工序、生产单或产品不匹配，请核实关联");
        }
    }

    private MesQualityInspection lockInspectionDraft(Long id) {
        if (id == null) throw BizException.of("检验单 id 必填");
        MesQualityInspection head = inspectionMapper.selectByIdForUpdate(id);
        if (head == null) throw BizException.of("检验单不存在");
        if (!Constants.STATUS_DRAFT.equals(head.getStatus())) throw BizException.of("该检验单已签核或不是草稿，不可修改");
        return head;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveProcessRecord(Long id, ProcessInspectionRecordDTO dto) {
        if (dto == null) throw BizException.of("检验记录不能为空");
        MesQualityInspection head = lockInspectionDraft(id);
        if (!"PRD_PROCESS".equals(head.getSourceBillType()) || !QmsIqcItemService.isProcessInspection(head)) {
            throw BizException.of("仅允许保存真实工序 PRD_PROCESS 的 FAI/PAT 检验记录");
        }
        PrdProcess process = requireProcess(head.getSourceBillId(), false);
        PrdOrder order = requireActiveOrder(process.getPrdOrderId());
        validateProcessHead(head, process, order);
        requirePatFai(process, head.getInspectionType());
        if (dto.getResult() != null && !Constants.RESULT_PASS.equals(dto.getResult()) && !Constants.RESULT_FAIL.equals(dto.getResult())) {
            throw BizException.of("签核结果仅支持 PASS/FAIL，保存草稿请传 null");
        }
        QmsIqcItemService.requireLength(dto.getBatchNo(), 64, "批次号");
        validatePhotos(dto.getPhotoUrls());
        if (dto.getSampleQty() != null) head.setSampleQty(dto.getSampleQty());
        if (dto.getQualifiedQty() != null) head.setQualifiedQty(dto.getQualifiedQty());
        if (dto.getUnqualifiedQty() != null) head.setUnqualifiedQty(dto.getUnqualifiedQty());
        validateQuantities(head.getSampleQty(), head.getQualifiedQty(), head.getUnqualifiedQty(), dto.getResult() != null);
        List<QmsIqcItem> existing = iqcItemMapper.selectByIqcId(id);
        validateTemplateCodes(head, existing);
        QmsIqcItemService.validateTemplateSubmission(existing, dto.getItems(), head.getInspectionType());
        if (dto.getResult() != null) validateProcessResult(head, dto.getItems(), dto.getResult());
        if (dto.getBatchNo() != null) head.setBatchNo(dto.getBatchNo().trim());
        if (dto.getPhotoUrls() != null) head.setPhotoUrls(StrUtil.isBlank(dto.getPhotoUrls()) ? "[]" : dto.getPhotoUrls());
        head.setInspector(SecurityContext.getUserId());
        head.setInspectorName(SecurityContext.getUsername());
        head.setResult(Constants.RESULT_HOLD);
        iqcItemService.batchSave(id, dto.getItems());
        if (inspectionMapper.updateById(head) != 1) throw BizException.of("检验单已变化，请刷新后重试");
        if (dto.getResult() != null) {
            check(id, dto.getResult(), Constants.RESULT_PASS.equals(dto.getResult()) ? "NONE" : Constants.BATCH_FROZEN,
                    Constants.RESULT_FAIL.equals(dto.getResult()) ? processFailureReason(head, dto.getItems()) : "工序逐项实测检验合格");
            head = inspectionMapper.selectById(id);
        }
        writeAudit("SAVE_PROCESS_RECORD", 1, "inspectionId=" + id + " processId=" + process.getId()
                + " result=" + dto.getResult() + " items=" + dto.getItems().size(), null);
        return processDetail(head, process, order);
    }

    private void validateQuantities(BigDecimal sample, BigDecimal good, BigDecimal bad, boolean signing) {
        for (BigDecimal qty : new BigDecimal[]{sample, good, bad}) {
            if (qty != null && (qty.signum() < 0 || qty.stripTrailingZeros().scale() > 4
                    || qty.compareTo(new BigDecimal("100000000000000")) >= 0)) {
                throw BizException.of("数量不能为负，最多14位整数和4位小数");
            }
        }
        if (sample != null && sample.signum() <= 0) throw BizException.of("样本数必须大于0");
        if (signing && (sample == null || good == null || bad == null)) throw BizException.of("签核必须填写样本数、合格数和不良数");
        if (sample != null && good != null && bad != null && sample.compareTo(good.add(bad)) != 0) {
            throw BizException.of("合格数与不良数之和必须等于样本数");
        }
    }

    private void validatePhotos(String photos) {
        if (StrUtil.isBlank(photos)) return;
        QmsIqcItemService.requireLength(photos, 16384, "照片列表");
        try {
            Object parsed = com.alibaba.fastjson2.JSON.parse(photos);
            if (!(parsed instanceof com.alibaba.fastjson2.JSONArray list)
                    || list.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException();
        } catch (Exception ex) {
            throw BizException.of("photoUrls 必须是照片 URL 字符串的 JSON 数组");
        }
    }

    private String processFailureReason(MesQualityInspection head, List<QmsIqcItem> items) {
        String failed = items.stream().filter(it -> Integer.valueOf(0).equals(it.getPassFlag()))
                .map(QmsIqcItem::getItemCode).collect(Collectors.joining(","));
        return StrUtil.maxLength("工序检验不合格；不合格项: " + (failed.isEmpty() ? "无" : failed)
                + "；不良数量: " + head.getUnqualifiedQty(), 255);
    }

    /** 查某工序实例关联的 PAT 巡检单 (最新一条, 含 DRAFT/CHECKED). */
    @Transactional(readOnly = true)
    public MesQualityInspection findPatByProcessId(Long processId) {
        if (processId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PRD_PROCESS")
                .eq(MesQualityInspection::getSourceBillId, processId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_PAT)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /** 某生产单下全部工序实例的 PAT 巡检记录 (PC 端工单详情看「该工单的巡检记录」). */
    @Transactional(readOnly = true)
    public java.util.List<MesQualityInspection> listPatByOrderNo(String prdOrderNo) {
        if (StrUtil.isBlank(prdOrderNo)) return java.util.Collections.emptyList();
        return inspectionMapper.selectList(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PRD_PROCESS")
                .eq(MesQualityInspection::getSourceBillNo, prdOrderNo)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_PAT)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByAsc(MesQualityInspection::getId));
    }

    /** 触发器共享内部方法: 单 head, 无 defects. */
    private Long createInternal(MesQualityInspection ins) {
        ins.setBillNo(billNoGenerator.generate(Constants.BILL_QI));
        inspectionMapper.insert(ins);
        log.info("[QMS] 触发器自动创建检验单 billNo={} type={} sourceBill={}",
                ins.getBillNo(), ins.getInspectionType(), ins.getSourceBillNo());
        return ins.getId();
    }

    // ==================================================================
    // 审计 (同步直写, McpAuditService 教训)
    // ==================================================================

    private void writeAudit(String method, int status, String what, String errMsg) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("质量检验");
            l.setBusinessType("QMS");
            l.setMethod("[QMS] " + method);
            l.setRequestMethod("POST");
            l.setRequestParam(what);
            if (errMsg != null) l.setErrorMsg(StrUtil.maxLength(errMsg, 1000));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(status);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[QMS] 审计写入失败 (不阻塞主流程): {}", e.getMessage());
        }
    }

    /** 调试辅助: 用于上游 service 排查. */
    public static boolean isFail(String result) {
        return Objects.equals(Constants.RESULT_FAIL, result)
                || Objects.equals(Constants.RESULT_FAIL_REJECT, result);
    }

    // ==================================================================
    // 5. v1.1.95 XK 三步采购入库 — 检验合格门禁 (先检验后入库)
    // ==================================================================

    /**
     * 查某采购入库单关联的 IQC 检验单 (最新一条, 含 DRAFT/CHECKED).
     * <p>source_bill_type='PUR_RECEIPT' AND source_bill_id=receiptId.</p>
     */
    @Transactional(readOnly = true)
    public MesQualityInspection findIqcByReceiptId(Long receiptId) {
        if (receiptId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PUR_RECEIPT")
                .eq(MesQualityInspection::getSourceBillId, receiptId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_IQC)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /**
     * v1.1.172: 入库审核门禁专用 — 找该入库单关联的合格 IQC, 支持 App 订单版签署 (PUR_ORDER 键回落).
     *
     * <p>App「生成验放单并入库」走订单版: IQC 的 source_bill_type=PUR_ORDER, source_bill_id=orderId.
     * 入库审核门禁原先只查 PUR_RECEIPT 键 → 查不到 → 误报「未关联 IQC」.
     * 本方法先查 PUR_RECEIPT 键, 没命中再按 receipt.orderId 回落查 PUR_ORDER 键 (同一订单可能多次入库,
     * 取最新一条已签署的 IQC).</p>
     *
     * @param receipt 采购入库单 (须有 id 与 orderId)
     * @return 关联的 IQC, 无则 null
     */
    @Transactional(readOnly = true)
    public MesQualityInspection findIqcForReceiptGate(com.industrial.erp.modules.purchase.entity.PurReceipt receipt) {
        if (receipt == null || receipt.getId() == null) return null;
        MesQualityInspection ins = findIqcByReceiptId(receipt.getId());
        if (ins != null) return ins;
        if (receipt.getOrderId() != null) {
            ins = findIqcByOrderId(receipt.getOrderId());
            if (ins != null) return ins;
        }
        return null;
    }

    /**
     * 该入库单是否已关联"合格"的 IQC (result=PASS 或 PASS_DISPOSITION, status=CHECKED).
     * <p>XK 门禁: 检验合格才允许审核入库. 无关联 / 未检(HOLD) / 不合格(FAIL*) 均返回 false.</p>
     */
    public boolean hasPassedIqc(Long receiptId) {
        MesQualityInspection ins = findIqcByReceiptId(receiptId);
        if (ins == null) return false;
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }

    /**
     * 把关联 IQC 的判定结果/报告号/抽样数/检验人 冗余快照进采购入库单 (供 XK 前端展示 + 审计).
     * <p>无关联检验单时字段保持 null (表示尚未检验). 不改检验单本身, 只写入库单冗余列.</p>
     */
    public void snapshotXkGate(PurReceipt receipt) {
        if (receipt == null || receipt.getId() == null) return;
        MesQualityInspection ins = findIqcByReceiptId(receipt.getId());
        if (ins == null) return;
        receipt.setInspectionResult(ins.getResult());
        receipt.setInspectionReportNo(ins.getBillNo());
        receipt.setSampleQty(ins.getSampleQty());
        receipt.setInspectorName(ins.getInspectorName());
    }

    /**
     * v1.1.95 XK 三步流程 — 为采购入库单「签署合格检验报告」: 一步建成/审核为 PASS 的 IQC.
     *
     * <p>逻辑: 找该入库单关联的 IQC (createOnReceipt 自动建过则复用, 否则新建 DRAFT),
     * 把前端提交的 抽样数/检验人/附件/照片 写进去, 然后 check(PASS, disposition=NONE) 放行.
     * 之后 {@code PurReceiptService.check()} 门禁 {@link #hasPassedIqc} 即放行入库.</p>
     *
     * @param receiptId  采购入库单 id
     * @param reportNo   检验报告号 (XK 展示; 为空则沿用/新生成 IQC billNo)
     * @param sampleQty  抽样数量
     * @param inspectorName 放行/检验人员
     * @param photoUrls  原料标签/抽样照片 URL (JSON 数组字符串, 可为空)
     * @return 签署后的 IQC 检验单 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long signPassedIqcForReceipt(Long receiptId, String reportNo,
                                         java.math.BigDecimal sampleQty,
                                         String inspectorName, String photoUrls) {
        if (receiptId == null) throw BizException.of("入库单 id 不能为空");
        PurReceipt receipt = findReceiptForGate(receiptId);
        MesQualityInspection ins = findIqcByReceiptId(receiptId);
        if (ins == null) {
            // 无关联 IQC → 先自动建一张 DRAFT (复用 createOnReceipt 的取样逻辑)
            ins = new MesQualityInspection();
            fillIqcHeadFromReceipt(ins, receipt);
            ins.setStatus(Constants.STATUS_DRAFT);
            ins.setResult(Constants.RESULT_HOLD);
            ins.setBillNo(billNoGenerator.generate(Constants.BILL_QI));
            inspectionMapper.insert(ins);
        }
        // 写 XK 展示/留痕字段
        // v1.1.158: 检验报告号系统自动生成 (BG-YL-YYYYMMDD-NNN).
        //   前端不再手填 reportNo (改只读), 传空 → 后端发号; 已有号 (重签/老数据) 则沿用不重复发.
        if (StrUtil.isBlank(reportNo)) {
            String existing = receipt.getInspectionReportNo();
            if (StrUtil.isNotBlank(existing)) {
                reportNo = existing;
            } else if (reportNoGenerator != null) {
                reportNo = reportNoGenerator.generate();
            } else {
                // 兜底 (无对账器注入, 极少见): 沿用 IQC 单号
                reportNo = ins.getBillNo();
            }
        }
        if (sampleQty != null) ins.setSampleQty(sampleQty);
        if (StrUtil.isNotBlank(inspectorName)) ins.setInspectorName(inspectorName);
        if (StrUtil.isNotBlank(photoUrls)) ins.setPhotoUrls(photoUrls);
        if (StrUtil.isNotBlank(reportNo)) ins.setRemark("XK 检验报告号 " + reportNo);
        // 审核放行 = PASS (不锁批, 仅写 DISPOSITION link 留痕)
        ins.setStatus(Constants.STATUS_DRAFT); // 确保走 check 逻辑
        check(ins.getId(), Constants.RESULT_PASS, "NONE", "XK 签署检验报告合格, 放行入库");
        // 冗余快照写回入库单 (供 XK 前端列表展示 报告号/判定/抽样/检验人)
        snapshotXkGate(receipt);
        receipt.setInspectionResult(Constants.RESULT_PASS);
        receipt.setInspectionReportNo(StrUtil.isBlank(reportNo) ? ins.getBillNo() : reportNo);
        receipt.setSampleQty(ins.getSampleQty());
        receipt.setInspectorName(StrUtil.isNotBlank(inspectorName) ? inspectorName : ins.getInspectorName());
        // 通过 receiptMapper 落库 (PurReceipt 实体已有 XK 字段)
        receiptMapper.updateById(receipt);
        writeAudit("SIGN_PASSED_IQC", 1, "receipt=" + receiptId + " reportNo=" + reportNo, null);
        log.info("[QMS] XK 签署合格检验: receipt={} iqc={} reportNo={}", receiptId, ins.getBillNo(), reportNo);
        return ins.getId();
    }

    /** 找入库单 (供签署流程) — 直接走 BaseMapper. */
    private PurReceipt findReceiptForGate(Long receiptId) {
        PurReceipt r = receiptMapper.selectById(receiptId);
        if (r == null) throw BizException.of("采购入库单不存在");
        return r;
    }

    /** 从入库单填充 IQC 头字段 (复用 createOnReceipt 的取样口径). */
    private void fillIqcHeadFromReceipt(MesQualityInspection ins, PurReceipt receipt) {
        ins.setInspectionType(Constants.INSPECTION_IQC);
        ins.setSourceBillType("PUR_RECEIPT");
        ins.setSourceBillId(receipt.getId());
        ins.setSourceBillNo(receipt.getBillNo());
        ins.setSupplierId(receipt.getSupplierId());
        List<PurReceiptDetail> details = purReceiptDetailMapper.selectByReceiptId(receipt.getId());
        if (details != null) {
            java.math.BigDecimal sampleQty = java.math.BigDecimal.ZERO;
            for (PurReceiptDetail d : details) {
                if (d.getQty() != null) sampleQty = sampleQty.add(d.getQty());
                if (ins.getProductId() == null && d.getProductId() != null) {
                    ins.setProductId(d.getProductId());
                    ins.setProductCode(d.getProductCode());
                    ins.setProductName(d.getProductName());
                    ins.setSpec(d.getSpec());
                }
                if (StrUtil.isBlank(ins.getBatchNo()) && StrUtil.isNotBlank(d.getBatchNo())) {
                    ins.setBatchNo(d.getBatchNo());
                }
            }
            ins.setSampleQty(sampleQty);
        }
        ins.setBillDate(LocalDate.now());
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
    }

    // ==================================================================
    // 6. v1.1.98 半成品入库 — FAI 门禁 + 触发器
    // ==================================================================

    /** 半成品入库审核通过 → 自动生成 FAI DRAFT 草稿. */
    @Transactional(rollbackFor = Exception.class)
    public Long createOnSemiIn(com.industrial.erp.modules.production.entity.PrdSemiIn semiIn) {
        if (semiIn == null || semiIn.getId() == null) return null;
        if (!isFoodGradeProduct(semiIn.getProductId())) return null;   // 工业品不建首检
        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_FAI);
        ins.setSourceBillType("PRD_SEMI_IN");
        ins.setSourceBillId(semiIn.getId());
        ins.setSourceBillNo(semiIn.getBillNo());
        ins.setProductId(semiIn.getProductId());
        ins.setProductCode(semiIn.getProductCode());
        ins.setProductName(semiIn.getProductName());
        ins.setSpec(semiIn.getSpec());
        ins.setBatchNo(semiIn.getBatchNo());
        ins.setWorkshop(semiIn.getRemark());  // 一期简化: remark 当车间记录
        ins.setSampleQty(semiIn.getQty());
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("半成品入库 " + semiIn.getBillNo() + " 自动生成 FAI 待检");
        Long id = createInternal(ins);
        ensureProcessTemplateItems(ins);
        return id;
    }

    /** 查某半成品入库单关联的 FAI 检验单 (最新一条, 含 DRAFT/CHECKED). */
    @Transactional(readOnly = true)
    public MesQualityInspection findFaiBySemiInId(Long semiInId) {
        if (semiInId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PRD_SEMI_IN")
                .eq(MesQualityInspection::getSourceBillId, semiInId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_FAI)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /**
     * 该半成品入库单是否已关联"合格"的 FAI (result=PASS/PASS_DISPOSITION, status=CHECKED).
     *
     * <p>v1.1.138: App 吹膜现场顺序是 开工 → 首检 → 半成品入库, 首检发生在入库单建出来之前,
     * 所以首检按「工单 + 实际吹膜工序」做 (挂在 PRD_PROCESS 上), 不再关联入库单 ——
     * 这里额外认工序侧那张, 否则食品级成品的入库会被门禁卡死。</p>
     */
    public boolean hasPassedFai(Long semiInId) {
        MesQualityInspection ins = findFaiBySemiInId(semiInId);
        if (ins == null) {
            PrdProcess blow = findBlowProcessOfSemiIn(semiInId);
            return blow != null && hasPassedFaiForProcess(blow.getId());
        }
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }

    /**
     * v1.1.138 — 半成品入库单关联的 FAI: 优先取挂在本入库单上的; 没有则取该工单吹膜工序上的首检
     * (App 现场先做首检、后建入库单, 那张挂在 PRD_PROCESS 上)。
     */
    @Transactional(readOnly = true)
    public MesQualityInspection findLinkedFaiForSemiIn(Long semiInId) {
        MesQualityInspection ins = findFaiBySemiInId(semiInId);
        if (ins != null) return ins;
        PrdProcess blow = findBlowProcessOfSemiIn(semiInId);
        return blow == null ? null : latestForProcess(blow.getId(), Constants.INSPECTION_FAI, false);
    }

    /** 该入库单所属工单上的吹膜工序 (已展开); 没有则 null。 */
    private PrdProcess findBlowProcessOfSemiIn(Long semiInId) {
        if (semiInId == null) return null;
        com.industrial.erp.modules.production.entity.PrdSemiIn si = prdSemiInMapper.selectById(semiInId);
        if (si == null || si.getPrdOrderId() == null) return null;
        List<PrdProcess> processes = prdProcessMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, si.getPrdOrderId())
                .eq(PrdProcess::getDeleted, 0)
                .orderByAsc(PrdProcess::getProcessNo));
        for (PrdProcess p : processes) {
            if (isExpandedProcess(p) && "BLOW".equals(processStage(p))) return p;
        }
        return null;
    }

    /** 把关联 FAI 判定结果/报告号/抽样数/检验人 冗余快照进半成品入库单 (仿 snapshotXkGate). */
    public void snapshotFaiGate(com.industrial.erp.modules.production.entity.PrdSemiIn semiIn) {
        if (semiIn == null || semiIn.getId() == null) return;
        MesQualityInspection ins = findLinkedFaiForSemiIn(semiIn.getId());
        if (ins == null) return;
        semiIn.setInspectionResult(ins.getResult());
        semiIn.setInspectionReportNo(ins.getBillNo());
        semiIn.setSampleQty(ins.getSampleQty());
        semiIn.setInspectorName(ins.getInspectorName());
    }

    // ==================================================================
    // 7. v1.1.99 C3 — 质量门禁对称化: OQC (成品入库) / OQC_RETURN (销售退货) 硬门禁
    //    仿 IQC/FAI 的 hasPassed* 那套, 让「先检后入」在成品/退货侧对称.
    // ==================================================================

    /** 查某成品入库单关联的 OQC 检验单 (最新一条, 含 DRAFT/CHECKED). */
    @Transactional(readOnly = true)
    public MesQualityInspection findOqcByFinishedInId(Long finishedInId) {
        if (finishedInId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PRD_FINISHED_IN")
                .eq(MesQualityInspection::getSourceBillId, finishedInId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_OQC)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /**
     * v1.1.102 — 该商品是否食品级 (base_product.is_food_grade=1).
     * 查不到/为空/为 0 一律按工业品返回 false (与 loadDefaultIqcItems 同口径).
     * 供 PrdSemiInService / PrdFinishedInService 判定 XK 门禁豁免: 仅食品级商品走 XK 门禁, 工业品直接放行.
     */
    public boolean isFoodGradeProduct(Long productId) {
        if (productId == null) return false;
        try {
            BaseProduct p = productMapper.selectById(productId);
            return p != null && p.getIsFoodGrade() != null && p.getIsFoodGrade() == 1;
        } catch (Exception e) {
            log.warn("[QMS-XK] isFoodGradeProduct 查商品失败 productId={} err={}", productId, e.getMessage());
            return false;
        }
    }

    /** 该生产单的商品是否食品级 (v1.1.150: 工业品不产生首检/巡检/成品检记录, 报工门禁也随之豁免). */
    public boolean isFoodGradeOrder(Long prdOrderId) {
        if (prdOrderId == null) return false;
        PrdOrder order = prdOrderMapper.selectById(prdOrderId);
        return order != null && isFoodGradeProduct(order.getProductId());
    }

    /**
     * v1.1.150 — 首检/巡检/成品检只对食品级商品产生记录; 工业品直接拒绝建单.
     * 口径沿用 {@link #isFoodGradeProduct} (查不到/为空/非 1 一律工业品), 与入库 XK 门禁同源.
     */
    private void requireFoodGradeForInspection(Long productId) {
        if (isFoodGradeProduct(productId)) return;
        throw BizException.of("该商品为工业品（非食品级），按现行口径不产生首检/巡检/成品检记录; "
                + "食品接触用包装请先在商品档案标记「食品级」");
    }

    /** 查某销售退货单关联的 OQC_RETURN 检验单 (最新一条). */
    @Transactional(readOnly = true)
    public MesQualityInspection findOqcReturnBySalReturnId(Long salReturnId) {
        if (salReturnId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "SAL_RETURN")
                .eq(MesQualityInspection::getSourceBillId, salReturnId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_OQC_RETURN)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /** 该销售退货单是否已关联"合格"的 OQC_RETURN. 注: 退货判定语义偏「处置放行」, PASS 系均放行. */
    public boolean hasPassedOqcReturn(Long salReturnId) {
        MesQualityInspection ins = findOqcReturnBySalReturnId(salReturnId);
        if (ins == null) return false;
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }

    // ==================================================================
    // 8. v1.1.101 XK — 原料进货检验工作流 (App 端 pages/scan/in 主流程)
    //    流程: ① 采购计划 → ② 到货与原料检验 (本 IQC) → ③ 自动入库
    // ==================================================================

    /**
     * v1.1.101 XK — 从采购计划触发, 自动建 IQC 草稿 + 加载默认检验项.
     *
     * <p>App 端 pages/scan/in onLoad(planId) 调本方法, 拿 iqcId 后回到 UI 编辑检验项/附件/照片.
     * 已存在关联 IQC 则复用 (幂等), 不重复创建.</p>
     *
     * @return 关联的 IQC 检验单 id (新建或复用)
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromPlan(PurInboundPlan plan) {
        if (plan == null || plan.getId() == null) throw BizException.of("采购计划不能为空");
        // 幂等: 已有关联 IQC 则直接返回
        MesQualityInspection exist = findIqcByPlanId(plan.getId());
        if (exist != null) {
            // 触发器: 老 IQC 缺默认检验项则补
            if (iqcItemMapper.selectByIqcId(exist.getId()).isEmpty()) {
                loadDefaultIqcItems(exist);
            }
            return exist.getId();
        }
        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_IQC);
        ins.setSourceBillType("PUR_INBOUND_PLAN");
        ins.setSourceBillId(plan.getId());
        ins.setSourceBillNo(plan.getPlanNo());
        ins.setSupplierId(plan.getSupplierId());
        ins.setProductId(plan.getProductId());
        ins.setProductCode(plan.getProductCode());
        ins.setProductName(plan.getProductName());
        ins.setSpec(plan.getSpec());
        ins.setSampleQty(plan.getPlanQty());  // 抽样数 = 计划到货数 (一期简化)
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("采购计划 " + plan.getPlanNo() + " 自动生成 IQC 待检");
        createInternal(ins);
        loadDefaultIqcItems(ins);
        writeAudit("CREATE_IQC_FROM_PLAN", 1,
                "planId=" + plan.getId() + " iqcId=" + ins.getId() + " billNo=" + ins.getBillNo(), null);
        log.info("[QMS-XK] createFromPlan: plan={} iqc={} items loaded",
                plan.getPlanNo(), ins.getBillNo());
        return ins.getId();
    }

    /**
     * v1.1.101 XK — 自动从 base_product.is_food_grade 加载 4-7 行默认检验项模板.
     * 工业级 4 项, 食品级 7 项 (工业 4 + 食品 3). 一期硬编码, 后续可改 base_product_iqc_template 表.
     */
    @Transactional(rollbackFor = Exception.class)
    public void loadDefaultIqcItems(MesQualityInspection ins) {
        if (ins == null || ins.getId() == null) return;
        boolean isFoodGrade = false;
        try {
            if (ins.getProductId() != null) {
                BaseProduct p = productMapper.selectById(ins.getProductId());
                if (p != null && p.getIsFoodGrade() != null && p.getIsFoodGrade() == 1) {
                    isFoodGrade = true;
                }
            }
        } catch (Exception ignore) {
            // 商品表查不到也不阻断, 走工业级默认
        }
        List<String[]> tpl = new ArrayList<>();
        for (String[] r : DEFAULT_IQC_ITEMS_COMMON) tpl.add(r);
        if (isFoodGrade) for (String[] r : DEFAULT_IQC_ITEMS_FOOD) tpl.add(r);
        int line = 1;
        // v1.1.184: 默认判定以模板第 6 列为唯一真源 (N/A=2 不适用 / PASS=1 合格),
        // 此前这里另有一套 NA 集合, 与模板列不一致 (食品接触重金属: 模板 PASS、代码 N/A),
        // 且保存闸又不认 N/A → App「保存检验记录」必然报「不能判不适用」。
        for (String[] r : tpl) {
            QmsIqcItem it = new QmsIqcItem();
            it.setIqcId(ins.getId());
            it.setLineNo(line++);
            it.setItemCode(r[0]);
            it.setItemName(r[1]);
            it.setRequirement(r[2]);
            it.setMethod(r[3]);
            it.setUnit(r[4]);
            it.setPassFlag(defaultIqcFlag(r));  // 2=不适用, 1=合格(默认)
            it.setIsFoodGradeItem(isFoodGrade ? 1 : 0);
            it.setSource("TEMPLATE");
            iqcItemMapper.insert(it);
        }
        log.info("[QMS-XK] loadDefaultIqcItems: iqc={} food={} items={}",
                ins.getBillNo(), isFoodGrade, tpl.size());
    }

    /** 进货检验模板第 6 列 → 默认判定: N/A=2 (公司入库不检验), PASS=1 (合格). */
    private static int defaultIqcFlag(String[] templateRow) {
        return templateRow.length > 5 && "N/A".equals(templateRow[5]) ? 2 : 1;
    }

    private String inspectionStage(MesQualityInspection ins) {
        if ("PRD_PROCESS".equals(ins.getSourceBillType()) && ins.getSourceBillId() != null) {
            return processStage(prdProcessMapper.selectById(ins.getSourceBillId()));
        }
        return "PRD_SEMI_IN".equals(ins.getSourceBillType()) && Constants.INSPECTION_FAI.equals(ins.getInspectionType())
                ? "BLOW" : null;
    }

    private String[][] commonProcessTemplate(String stage, String type) {
        if ("BAG".equals(stage)) return Constants.INSPECTION_FAI.equals(type) ? BAG_FAI_ITEMS : BAG_PAT_ITEMS;
        return Constants.INSPECTION_FAI.equals(type) ? DEFAULT_FAI_ITEMS_COMMON : DEFAULT_PAT_ITEMS_COMMON;
    }

    /**
     * v1.1.134 同日修补 食品级追加项按「工序阶段 + 检验类型」分开: 吹膜首检比巡检少两项
     * (迁移检测报告、食品接触标识), 巡检与制袋模板不变。
     */
    private String[][] foodProcessTemplate(String stage, String type) {
        if ("BAG".equals(stage)) return BAG_FOOD_ITEMS;
        return Constants.INSPECTION_FAI.equals(type) ? DEFAULT_FAI_ITEMS_FOOD : DEFAULT_PROCESS_ITEMS_FOOD;
    }

    private boolean processFoodGrade(MesQualityInspection ins) {
        if (ins.getProductId() == null) throw BizException.of("检验单缺少产品，无法确定食品级模板");
        BaseProduct product = productMapper.selectById(ins.getProductId());
        if (product == null) throw BizException.of("检验产品不存在，无法确定食品级模板");
        return Integer.valueOf(1).equals(product.getIsFoodGrade());
    }

    private void ensureProcessTemplateItems(MesQualityInspection ins) {
        if (ins == null || ins.getId() == null || !Constants.STATUS_DRAFT.equals(ins.getStatus())
                || !QmsIqcItemService.isProcessInspection(ins)) return;
        String stage = inspectionStage(ins);
        if (stage == null || !iqcItemMapper.selectByIqcId(ins.getId()).isEmpty()) return;
        boolean food = processFoodGrade(ins);
        int line = 1;
        for (String[] row : commonProcessTemplate(stage, ins.getInspectionType())) insertTemplateItem(ins, line++, row, 0);
        if (food) {
            for (String[] row : foodProcessTemplate(stage, ins.getInspectionType())) {
                insertTemplateItem(ins, line++, row, 1);
            }
        }
    }

    /** v1.1.131 成品检验 — OQC/OQC_RETURN 也走服务器模板 (签核前逐项实测, 不允许删项). */
    private boolean isFinishedInspection(MesQualityInspection ins) {
        return ins != null && (Constants.INSPECTION_OQC.equals(ins.getInspectionType())
                || Constants.INSPECTION_OQC_RETURN.equals(ins.getInspectionType()));
    }

    /**
     * 成品检验模板判型: 工单工序里出现制袋 → 袋模板; 否则按吹膜膜卷模板.
     * 退货单 (SAL_RETURN) 无产品/工单时返回 BAG_BLANK 由调用方兜底.
     *
     * <p>v1.1.135: App 端「生产单详情 → 做成品检」建的 OQC 挂在 PRD_ORDER 上 (彼时还没有成品入库单),
     * 判型口径与入库单侧一致, 都按该工单的工序链判膜/袋。
     */
    private String oqcStage(MesQualityInspection ins) {
        if (ins == null || ins.getSourceBillId() == null) return null;
        if ("PRD_FINISHED_IN".equals(ins.getSourceBillType())) {
            PrdFinishedIn fi = prdFinishedInMapper.selectById(ins.getSourceBillId());
            return fi == null ? null : orderStage(fi.getPrdOrderId());
        }
        if ("PRD_ORDER".equals(ins.getSourceBillType())) {
            return orderStage(ins.getSourceBillId());
        }
        return null;
    }

    /** 某生产单的成品的类型: 工序链里有制袋 → BAG, 有其它工序 → BLOW, 没展开工序 → null (调用方兜底膜卷). */
    private String orderStage(Long prdOrderId) {
        if (prdOrderId == null) return null;
        List<PrdProcess> processes = prdProcessMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, prdOrderId)
                .eq(PrdProcess::getDeleted, 0)
                .orderByAsc(PrdProcess::getProcessNo));
        for (PrdProcess p : processes) {
            if ("BAG".equals(processStage(p))) return "BAG";
        }
        return processes.isEmpty() ? null : "BLOW";
    }

    /**
     * v1.1.131 — 灌成品检验模板 (幂等: 已有检验项直接返回).
     * 膜卷/袋两套通用项 + 食品级产品追加 GB 4806 符合性项; 退货复检单独一套.
     */
    private void ensureOqcTemplateItems(MesQualityInspection ins) {
        if (ins == null || ins.getId() == null || !Constants.STATUS_DRAFT.equals(ins.getStatus())
                || !isFinishedInspection(ins)) return;
        if (!iqcItemMapper.selectByIqcId(ins.getId()).isEmpty()) return;
        String[][] tpl;
        if (Constants.INSPECTION_OQC_RETURN.equals(ins.getInspectionType())) {
            tpl = OQC_RETURN_ITEMS;
        } else {
            tpl = "BAG".equals(oqcStage(ins)) ? OQC_BAG_ITEMS : OQC_FILM_ITEMS;
        }
        int line = 1;
        for (String[] row : tpl) insertTemplateItem(ins, line++, row, 0);
        if (Constants.INSPECTION_OQC.equals(ins.getInspectionType()) && oqcFoodGrade(ins)) {
            for (String[] row : OQC_FOOD_ITEMS) insertTemplateItem(ins, line++, row, 1);
        }
        log.info("[QMS-XK] 成品检验模板灌入 billNo={} type={} items={}",
                ins.getBillNo(), ins.getInspectionType(), line - 1);
    }

    /** 成品检验食品级判定: 优先取商品 is_food_grade; 退货单无商品时按公司默认食品级从严. */
    private boolean oqcFoodGrade(MesQualityInspection ins) {
        if (ins.getProductId() != null) {
            BaseProduct product = productMapper.selectById(ins.getProductId());
            return product == null || Integer.valueOf(1).equals(product.getIsFoodGrade());
        }
        return true;
    }

    /**
     * v1.1.131 — 找/建某成品入库单的 OQC 草稿并灌模板, 一次回传 head+items (PC/App 成品检录入入口).
     * 幂等: 已有 OQC 复用; 已 CHECKED 也回传 (只读展示).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureOqcForFinishedIn(Long finishedInId) {
        if (finishedInId == null) throw BizException.of("finishedInId 必填");
        MesQualityInspection ins = findOqcByFinishedInId(finishedInId);
        if (ins == null) {
            PrdFinishedIn fi = prdFinishedInMapper.selectById(finishedInId);
            if (fi == null) throw BizException.of("成品入库单不存在: " + finishedInId);
            requireFoodGradeForInspection(fi.getProductId());
            Long id = createOnFinish(fi);
            if (id == null) throw BizException.of("无法为该成品入库单建 OQC 检验单");
            ins = inspectionMapper.selectById(id);
        }
        ensureOqcTemplateItems(ins);
        Map<String, Object> m = new HashMap<>();
        m.put("head", inspectionMapper.selectById(ins.getId()));
        m.put("items", iqcItemMapper.selectByIqcId(ins.getId()));
        m.put("finishedIn", prdFinishedInMapper.selectById(finishedInId));
        return m;
    }

    /**
     * v1.1.135 — App「生产单详情 → 做成品检」入口: 按生产单找/建 OQC 草稿并灌模板, 一次回传 head+items.
     *
     * <p>现场顺序是先成品检、后成品入库, 点这一步时往往还没有 {@code prd_finished_in} 单, 所以表头挂
     * {@code PRD_ORDER}; 判膜/袋仍按该工单的工序链 ({@link #orderStage}). 后建出来的成品入库单会把这张
     * DRAFT 归并到自己名下 ({@link #relinkOqcToFinishedIn}), 一张工单始终只留一条出厂检验记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureOqcForOrder(Long orderId) {
        PrdOrder order = requireOrder(orderId);
        if (order.getProductId() == null) throw BizException.of("生产单未关联产品，不能开成品检验");
        MesQualityInspection ins = findLinkedOqcByOrder(order.getId());
        if (ins == null) {
            requireFoodGradeForInspection(order.getProductId());
            ins = new MesQualityInspection();
            ins.setInspectionType(Constants.INSPECTION_OQC);
            ins.setSourceBillType("PRD_ORDER");
            ins.setSourceBillId(order.getId());
            ins.setSourceBillNo(order.getBillNo());
            ins.setProductId(order.getProductId());
            ins.setProductCode(order.getProductCode());
            ins.setProductName(order.getProductName());
            ins.setSpec(order.getSpec());
            ins.setBatchNo(order.getBatchNo());
            ins.setWorkshop(order.getWorkshop());
            ins.setBillDate(LocalDate.now());
            ins.setStatus(Constants.STATUS_DRAFT);
            ins.setResult(Constants.RESULT_HOLD);
            ins.setInspector(SecurityContext.getUserId());
            ins.setInspectorName(SecurityContext.getUsername());
            ins.setRemark("生产工单 " + order.getBillNo() + " 现场成品检验");
            Long id = createInternal(ins);
            if (id == null) throw BizException.of("无法为该生产单建 OQC 检验单");
            ins = inspectionMapper.selectById(id);
        }
        ensureOqcTemplateItems(ins);
        Map<String, Object> m = new HashMap<>();
        m.put("head", inspectionMapper.selectById(ins.getId()));
        m.put("items", iqcItemMapper.selectByIqcId(ins.getId()));
        m.put("order", order);
        m.put("stage", orderStage(order.getId()));
        return m;
    }

    /** 查某生产单关联的 OQC 检验单 (最新一条, 含 DRAFT/CHECKED)。 */
    @Transactional(readOnly = true)
    public MesQualityInspection findOqcByOrderId(Long prdOrderId) {
        if (prdOrderId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PRD_ORDER")
                .eq(MesQualityInspection::getSourceBillId, prdOrderId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_OQC)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /**
     * 该生产单当前唯一有效的出厂检验单: 优先生产单侧草稿, 其次该单任一成品入库单侧的 OQC
     * (入库单建出来以后表头会归并过去)。App 成品检页与成品入库门禁共用这一口径, 避免出现两张出厂检验记录。
     */
    @Transactional(readOnly = true)
    public MesQualityInspection findLinkedOqcByOrder(Long prdOrderId) {
        if (prdOrderId == null) return null;
        MesQualityInspection own = findOqcByOrderId(prdOrderId);
        if (own != null) return own;
        List<PrdFinishedIn> bills = prdFinishedInMapper.selectList(new LambdaQueryWrapper<PrdFinishedIn>()
                .eq(PrdFinishedIn::getPrdOrderId, prdOrderId)
                .eq(PrdFinishedIn::getDeleted, 0)
                .orderByDesc(PrdFinishedIn::getId));
        for (PrdFinishedIn fi : bills) {
            MesQualityInspection ins = findOqcByFinishedInId(fi.getId());
            if (ins != null) return ins;
        }
        return null;
    }

    /** 该生产单是否已有「合格放行」的出厂检验单 (生产单侧或入库单侧均算)。 */
    public boolean hasPassedOqcForOrder(Long prdOrderId) {
        return isPassedOqc(findLinkedOqcByOrder(prdOrderId));
    }

    /** OQC 表头是否已合格放行 (CHECKED + PASS/PASS_DISPOSITION)。 */
    public boolean isPassedOqc(MesQualityInspection ins) {
        if (ins == null || !Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }

    /**
     * v1.1.135 — 成品入库单建出来时, 把该工单已有的 OQC 草稿归并到这张入库单名下 (不新建第二张)。
     *
     * <p>只动 DRAFT (还没签核, 没有留痕可破坏); 已 CHECKED 的原样保留 —— XK 过程/出厂记录不可被后续单据改写,
     * 入库门禁改用 {@link #findLinkedOqcByOrder} 口径认它。
     *
     * @return true 表示已有单据、调用方不必再 createOnFinish
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean relinkOqcToFinishedIn(PrdFinishedIn finishedIn) {
        if (finishedIn == null || finishedIn.getPrdOrderId() == null) return false;
        MesQualityInspection ins = findLinkedOqcByOrder(finishedIn.getPrdOrderId());
        if (ins == null) return false;
        if (!Constants.STATUS_DRAFT.equals(ins.getStatus())) return true;
        ins.setSourceBillType("PRD_FINISHED_IN");
        ins.setSourceBillId(finishedIn.getId());
        ins.setSourceBillNo(finishedIn.getBillNo());
        if (StrUtil.isBlank(ins.getBatchNo())) ins.setBatchNo(finishedIn.getBatchNo());
        if (ins.getSampleQty() == null) ins.setSampleQty(finishedIn.getQty());
        if (inspectionMapper.updateById(ins) != 1) throw BizException.of("成品检验单已变化，请刷新后重试");
        log.info("[QMS-XK] OQC 草稿归并到成品入库单 oqcBillNo={} finishedInNo={}", ins.getBillNo(), finishedIn.getBillNo());
        return true;
    }

    /**
     * v1.1.131 — 按检验单 id 幂等灌模板 (OQC/OQC_RETURN 成品检, FAI/PAT 工序检), 回传 head+items.
     * PC 检验项录入面板打开草稿时调用, 老草稿借此补齐模板项.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureTemplateItems(Long inspectionId) {
        if (inspectionId == null) throw BizException.of("inspectionId 必填");
        MesQualityInspection ins = inspectionMapper.selectByIdForUpdate(inspectionId);
        if (ins == null) throw BizException.of("检验单不存在: " + inspectionId);
        if (isFinishedInspection(ins)) ensureOqcTemplateItems(ins);
        else ensureProcessTemplateItems(ins);
        MesQualityInspection head = inspectionMapper.selectById(inspectionId);
        stampStage(java.util.Collections.singletonList(head));
        Map<String, Object> m = new HashMap<>();
        m.put("head", head);
        m.put("stage", head.getStage());
        m.put("stageLabel", head.getStageLabel());
        m.put("processName", head.getProcessName());
        m.put("items", iqcItemMapper.selectByIqcId(inspectionId));
        return m;
    }

    /**
     * v1.1.149 — 本公司不做印刷, 达因/印刷质量相关的 4 项默认判「不适用」(pass_flag=2);
     * 其余模板项默认判「合格」(pass_flag=1)。实测值不算默认值, 现场仍须逐项填写。
     * 清单见 {@link com.industrial.erp.modules.qms.QmsItemCodes#NA_ONLY_ITEMS}。
     */
    private static final Set<String> NA_ITEM_CODES = QmsItemCodes.NA_ONLY_ITEMS;

    /** pass_flag: -1 待检 / 0 不合格 / 1 合格 / 2 不适用 (v1.1.149 起). */
    static boolean isJudgedFlag(Integer flag) {
        return Integer.valueOf(0).equals(flag) || Integer.valueOf(1).equals(flag) || Integer.valueOf(2).equals(flag);
    }

    static boolean isNaFlag(Integer flag) {
        return Integer.valueOf(2).equals(flag);
    }

    /** 「不适用」只开给达因/印刷质量 4 项, 其余项不得用不适用规避实测. */
    private static void requireNaOnlyForPrintOrCorona(QmsIqcItem item) {
        if (isNaFlag(item.getPassFlag()) && !NA_ITEM_CODES.contains(item.getItemCode())) {
            throw BizException.of("检验项 " + item.getItemCode() + " 不属于达因/印刷质量项，不能判不适用");
        }
    }

    private void insertTemplateItem(MesQualityInspection ins, int lineNo, String[] r, int foodItem) {
        QmsIqcItem it = new QmsIqcItem();
        it.setIqcId(ins.getId());
        it.setLineNo(lineNo);
        it.setItemCode(r[0]);
        it.setItemName(r[1]);
        it.setRequirement(r[2]);
        it.setMethod(r[3]);
        it.setUnit(r[4]);
        it.setPassFlag(NA_ITEM_CODES.contains(r[0]) ? 2 : 1);
        it.setIsFoodGradeItem(foodItem);
        it.setSource("TEMPLATE");
        iqcItemMapper.insert(it);
    }

    private void validateTemplateCodes(MesQualityInspection head, List<QmsIqcItem> items) {
        if (items == null || items.isEmpty()) throw BizException.of("检验单无模板项，不能签核或以手工项替代");
        String stage = inspectionStage(head);
        if (stage == null) throw BizException.of("无法识别检验工序，不能套用吹膜模板签核");
        Set<String> expected = new HashSet<>();
        for (String[] row : commonProcessTemplate(stage, head.getInspectionType())) expected.add(row[0]);
        boolean food = processFoodGrade(head);
        if (food) for (String[] row : foodProcessTemplate(stage, head.getInspectionType())) expected.add(row[0]);
        Set<String> actual = new HashSet<>();
        for (QmsIqcItem item : items) {
            QmsIqcItemService.validateItem(item, head.getInspectionType());
            requireNaOnlyForPrintOrCorona(item);
            if (!actual.add(item.getItemCode()) || (item.getSource() != null && !"TEMPLATE".equals(item.getSource()))) {
                throw BizException.of("模板项重复或被手工项替代，不能签核");
            }
        }
        if (!actual.equals(expected)) throw BizException.of("检验模板代码集合不完整或与" + stage + "工序不匹配，请重新打开首检/巡检页按最新模板灌入，不能删项/替代签核");
    }

    private void validateProcessResult(MesQualityInspection head, List<QmsIqcItem> items, String result) {
        if (!Constants.RESULT_PASS.equals(result) && !Constants.RESULT_FAIL.equals(result)) {
            throw BizException.of("FAI/PAT 签核仅支持 PASS 或 FAIL");
        }
        validateTemplateCodes(head, items);
        for (QmsIqcItem item : items) {
            if (!isJudgedFlag(item.getPassFlag())
                    || (!isNaFlag(item.getPassFlag()) && StrUtil.isBlank(item.getResultValue()))) {
                throw BizException.of("检验项 " + item.getItemCode() + " 未完成实测及判定，不能签核");
            }
        }
        boolean failed = items.stream().anyMatch(it -> Integer.valueOf(0).equals(it.getPassFlag()));
        boolean badQty = head.getUnqualifiedQty() != null && head.getUnqualifiedQty().signum() > 0;
        if (Constants.RESULT_PASS.equals(result) && (failed || badQty)) throw BizException.of("存在不合格项或不良数量，不能签核 PASS");
        if (Constants.RESULT_FAIL.equals(result) && !failed && !badQty) throw BizException.of("FAIL 必须至少有一项不合格或不良数量大于0");
    }

    /**
     * v1.1.131 — 成品检验 (OQC / OQC_RETURN) 出厂放行校验.
     *
     * <p>XK16-204 出厂检验要求逐检验项留痕: 每项必须有实测/观察记录和判定结论,
     * 放行 (PASS / PASS_DISPOSITION) 不得存在不合格项; 不合格路径必须能指出不合格项或写明原因。
     * 模板项由服务端灌入, 客户端不得删项或以手工项替代 (见 QmsIqcItemService 模板保护)。
     */
    private void validateFinishedResult(MesQualityInspection head, List<QmsIqcItem> items,
                                        String result, String reason) {
        if (items.isEmpty()) throw BizException.of("成品检验模板项缺失，请先灌入检验模板再签核");
        boolean release = Constants.RESULT_PASS.equals(result)
                || Constants.RESULT_PASS_DISPOSITION.equals(result);
        // 放行须落齐检验数量 (XK16-204 出厂检验记录含检验数量/合格数量); 待判定路径不强制
        validateQuantities(head.getSampleQty(), head.getQualifiedQty(), head.getUnqualifiedQty(), release);
        for (QmsIqcItem item : items) {
            requireNaOnlyForPrintOrCorona(item);
            if (!isJudgedFlag(item.getPassFlag())
                    || (!isNaFlag(item.getPassFlag()) && StrUtil.isBlank(item.getResultValue()))) {
                if (!release) continue;  // 待判定 (HOLD) 允许部分未检
                throw BizException.of("检验项 " + item.getItemName() + " 未完成实测及判定，不能放行");
            }
        }
        if (!release) return;
        String failedCodes = items.stream()
                .filter(it -> Integer.valueOf(0).equals(it.getPassFlag()))
                .map(QmsIqcItem::getItemName).collect(Collectors.joining("、"));
        if (!failedCodes.isEmpty()) throw BizException.of("存在不合格检验项: " + failedCodes + "，不能放行");
    }

    /**
     * v1.1.131 — App 首检页选单入口: 找/建该半成品入库单的 FAI + 灌吹膜模板, 一次回传 head+items.
     * 幂等: FAI 已存在则复用并补模板 (空明细才灌). 已 CHECKED 的 FAI 也回传 (App 显示已签).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureFaiForSemiIn(Long semiInId) {
        if (semiInId == null) throw BizException.of("semiInId 必填");
        MesQualityInspection ins = findFaiBySemiInId(semiInId);
        if (ins == null) {
            com.industrial.erp.modules.production.entity.PrdSemiIn si = prdSemiInMapper.selectById(semiInId);
            if (si == null) throw BizException.of("半成品入库单不存在: " + semiInId);
            requireFoodGradeForInspection(si.getProductId());
            Long id = createOnSemiIn(si);
            if (id == null) throw BizException.of("无法为该半成品入库单建 FAI 检验单");
            ins = inspectionMapper.selectById(id);
        }
        ensureProcessTemplateItems(ins);
        Map<String, Object> m = new HashMap<>();
        m.put("head", ins);
        m.put("items", iqcItemMapper.selectByIqcId(ins.getId()));
        return m;
    }

    /**
     * v1.1.101 XK — App 端 "生成报告并自动入库" 入口.
     *
     * <p>三步原子: ① 校验所有检验项 pass_flag=1 ② 写报告号/抽样数/检验人 ③ QmsInspectionService.check(PASS)
     * 失败抛 BizException (任一检验项 pass_flag=0 → 拒绝审核).</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void signPassedIqcForPlan(Long planId, Long iqcId, String reportNo,
                                      java.math.BigDecimal sampleQty,
                                      String inspectorName, String photoUrls) {
        if (planId == null || iqcId == null) throw BizException.of("planId / iqcId 必填");
        MesQualityInspection ins = inspectionMapper.selectById(iqcId);
        if (ins == null) throw BizException.of("IQC 检验单不存在");
        if (!"PUR_INBOUND_PLAN".equals(ins.getSourceBillType()) || !planId.equals(ins.getSourceBillId())) {
            throw BizException.of("IQC 与采购计划不匹配");
        }
        // 硬门禁: 全部检验项必须 pass_flag=1
        int failCount = iqcItemMapper.countFailByIqcId(iqcId);
        if (failCount > 0) {
            throw BizException.of("IQC 存在 " + failCount + " 项不合格检验项, 不能放行入库");
        }
        if (sampleQty != null) ins.setSampleQty(sampleQty);
        if (StrUtil.isNotBlank(inspectorName)) ins.setInspectorName(inspectorName);
        if (StrUtil.isNotBlank(photoUrls)) ins.setPhotoUrls(photoUrls);
        if (StrUtil.isNotBlank(reportNo)) {
            ins.setRemark((StrUtil.isBlank(ins.getRemark()) ? "" : ins.getRemark() + "; ")
                    + "XK 报告号 " + reportNo);
        }
        // 走 check(PASS) — 复用既有的批次联动 + 审计逻辑
        check(ins.getId(), Constants.RESULT_PASS, "NONE", "XK 原料进货检验合格, 放行入库");
        // 更新 plan 状态 INSPECTED (后续 PurReceiptService.createFromIqc 会再 → INBOUNDED)
        if (inboundPlanMapper != null) {
            inboundPlanMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PurInboundPlan>()
                    .eq(PurInboundPlan::getId, planId)
                    .set(PurInboundPlan::getPlanStatus, "INSPECTED")
                    .set(PurInboundPlan::getUpdateTime, LocalDateTime.now()));
        }
        writeAudit("SIGN_PASSED_IQC_FOR_PLAN", 1,
                "planId=" + planId + " iqcId=" + iqcId + " reportNo=" + reportNo, null);
        log.info("[QMS-XK] signPassedIqcForPlan: plan={} iqc={} reportNo={}",
                planId, ins.getBillNo(), reportNo);
    }

    /**
     * v1.1.101 XK — PurReceiptService.createFromIqc 门禁.
     * 关联到 planId 的 IQC 必须 CHECKED + PASS, 才允许自动建入库单.
     */
    public boolean hasPassedIqcForPlan(Long planId, Long iqcId) {
        if (planId == null || iqcId == null) return false;
        MesQualityInspection ins = inspectionMapper.selectById(iqcId);
        if (ins == null) return false;
        if (!planId.equals(ins.getSourceBillId())) return false;
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }

    /** 查某采购计划关联的 IQC (最新一条). 仿 findIqcByReceiptId. */
    @Transactional(readOnly = true)
    public MesQualityInspection findIqcByPlanId(Long planId) {
        if (planId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PUR_INBOUND_PLAN")
                .eq(MesQualityInspection::getSourceBillId, planId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_IQC)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    // ==================================================================
    // v1.1.101 订单为源 (完全对齐 PC: 采购订单 → IQC → 自动入库)
    // ==================================================================

    /** 查某采购订单关联的 IQC (最新一条). */
    @Transactional(readOnly = true)
    public MesQualityInspection findIqcByOrderId(Long orderId) {
        if (orderId == null) return null;
        return inspectionMapper.selectOne(new LambdaQueryWrapper<MesQualityInspection>()
                .eq(MesQualityInspection::getSourceBillType, "PUR_ORDER")
                .eq(MesQualityInspection::getSourceBillId, orderId)
                .eq(MesQualityInspection::getInspectionType, Constants.INSPECTION_IQC)
                .eq(MesQualityInspection::getDeleted, 0)
                .orderByDesc(MesQualityInspection::getId)
                .last("LIMIT 1"));
    }

    /**
     * v1.1.101 订单为源 — 从采购订单触发建/复用 IQC 草稿.
     * 幂等: 已有关联 IQC (sourceBillType=PUR_ORDER) 直接返回.
     * 商品/供应商/数量取订单第一行明细 (一期简化, 与 plan 路径一致).
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromOrder(com.industrial.erp.modules.purchase.entity.PurOrder order) {
        if (order == null || order.getId() == null) throw BizException.of("采购订单不能为空");
        MesQualityInspection exist = findIqcByOrderId(order.getId());
        if (exist != null) {
            if (iqcItemMapper.selectByIqcId(exist.getId()).isEmpty()) {
                loadDefaultIqcItems(exist);
            }
            return exist.getId();
        }
        // 取订单第一行明细作为 IQC 商品上下文
        List<com.industrial.erp.modules.purchase.entity.PurOrderDetail> dtls =
                orderDetailMapper.selectByOrderId(order.getId());
        com.industrial.erp.modules.purchase.entity.PurOrderDetail first =
                (dtls == null || dtls.isEmpty()) ? null : dtls.get(0);

        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_IQC);
        ins.setSourceBillType("PUR_ORDER");
        ins.setSourceBillId(order.getId());
        ins.setSourceBillNo(order.getBillNo());
        ins.setSupplierId(order.getSupplierId());
        if (first != null) {
            ins.setProductId(first.getProductId());
            ins.setProductCode(first.getProductCode());
            ins.setProductName(first.getProductName());
            ins.setSpec(first.getSpec());
        }
        ins.setSampleQty(order.getTotalQty());  // 抽样数 = 订单总量 (一期简化)
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("采购订单 " + order.getBillNo() + " 自动生成 IQC 待检");
        createInternal(ins);
        loadDefaultIqcItems(ins);
        writeAudit("CREATE_IQC_FROM_ORDER", 1,
                "orderId=" + order.getId() + " iqcId=" + ins.getId() + " billNo=" + ins.getBillNo(), null);
        log.info("[QMS-XK] createFromOrder: order={} iqc={} (食品级商品: {})",
                order.getBillNo(), ins.getBillNo(), ins.getProductId());
        return ins.getId();
    }

    /**
     * v1.1.101 订单为源 — App "生成报告并自动入库": 校验全部检验项合格 + check(PASS).
     * 对齐 signPassedIqcForPlan, 但 sourceBillType=PUR_ORDER.
     */
    @Transactional(rollbackFor = Exception.class)
    public void signPassedIqcForOrder(Long orderId, Long iqcId, String reportNo,
                                       java.math.BigDecimal sampleQty,
                                       String inspectorName, String photoUrls) {
        if (orderId == null || iqcId == null) throw BizException.of("orderId / iqcId 必填");
        MesQualityInspection ins = inspectionMapper.selectById(iqcId);
        if (ins == null) throw BizException.of("IQC 检验单不存在");
        if (!"PUR_ORDER".equals(ins.getSourceBillType()) || !orderId.equals(ins.getSourceBillId())) {
            throw BizException.of("IQC 与采购订单不匹配");
        }
        int failCount = iqcItemMapper.countFailByIqcId(iqcId);
        if (failCount > 0) {
            throw BizException.of("IQC 存在 " + failCount + " 项不合格检验项, 不能放行入库");
        }
        if (sampleQty != null) ins.setSampleQty(sampleQty);
        if (StrUtil.isNotBlank(inspectorName)) ins.setInspectorName(inspectorName);
        if (StrUtil.isNotBlank(photoUrls)) ins.setPhotoUrls(photoUrls);
        if (StrUtil.isNotBlank(reportNo)) {
            ins.setRemark((StrUtil.isBlank(ins.getRemark()) ? "" : ins.getRemark() + "; ")
                    + "XK 报告号 " + reportNo);
        }
        check(ins.getId(), Constants.RESULT_PASS, "NONE", "XK 原料进货检验合格, 放行入库");
        writeAudit("SIGN_PASSED_IQC_FOR_ORDER", 1,
                "orderId=" + orderId + " iqcId=" + iqcId + " reportNo=" + reportNo, null);
        log.info("[QMS-XK] signPassedIqcForOrder: order={} iqc={} reportNo={}",
                orderId, ins.getBillNo(), reportNo);
    }

    /** 订单版门禁: 关联到 orderId 的 IQC 必须 CHECKED + PASS. */
    public boolean hasPassedIqcForOrder(Long orderId, Long iqcId) {
        if (orderId == null || iqcId == null) return false;
        MesQualityInspection ins = inspectionMapper.selectById(iqcId);
        if (ins == null) return false;
        if (!orderId.equals(ins.getSourceBillId())) return false;
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }


    // ==================================================================
    // v1.1.101 App XK 吹膜生产全流程 — 半成品 FAI / 成品 OQC 现场签合格
    //    (检验员在 App 端逐检验项打分 + 拍照后, 直接 check(PASS) 写回门禁,
    //     仓管随即能过入库门禁; 后端门禁: 含 qms_iqc_item 明细则全合格才放行)
    // ==================================================================

    /**
     * 查某半成品入库单关联的 FAI 检验项是否全部合格 (无明细 → true, 兼容老数据/手建 FAI).
     */
    private boolean allItemsPassedOrEmpty(Long insId) {
        if (insId == null) return true;
        try {
            return iqcItemMapper.countFailByIqcId(insId) == 0;
        } catch (Exception e) {
            log.warn("[QMS-XK] countFailByIqcId 失败 insId={} err={}", insId, e.getMessage());
            return true;
        }
    }

    /**
     * App 端现场签合格 — 半成品入库 FAI 版.
     * 找/建该入库单关联的 FAI 草稿, 若含 qms_iqc_item 明细则全合格才放行, 然后 check(PASS).
     * 放行后 PrdSemiInService.check 的 hasPassedFai 门禁即转绿.
     */
    @Transactional(rollbackFor = Exception.class)
    public void signPassedFaiForSemiIn(Long semiInId, Long faiId, String reportNo,
                                        java.math.BigDecimal sampleQty,
                                        String inspectorName, String photoUrls) {
        if (semiInId == null) throw BizException.of("semiInId 必填");
        MesQualityInspection ins = (faiId != null) ? inspectionMapper.selectById(faiId) : null;
        if (ins == null) ins = findFaiBySemiInId(semiInId);
        if (ins == null) throw BizException.of("请先创建 FAI 并逐项填写实测记录，不能临签生成合格报告");
        ins = lockInspectionDraft(ins.getId());
        if (!semiInId.equals(ins.getSourceBillId()) || !"PRD_SEMI_IN".equals(ins.getSourceBillType())
                || !Constants.INSPECTION_FAI.equals(ins.getInspectionType())) throw BizException.of("FAI 与半成品入库单不匹配");
        if (sampleQty != null) ins.setSampleQty(sampleQty);
        validateQuantities(ins.getSampleQty(), ins.getQualifiedQty(), ins.getUnqualifiedQty(), false);
        validateProcessResult(ins, iqcItemMapper.selectByIqcId(ins.getId()), Constants.RESULT_PASS);
        validatePhotos(photoUrls);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        if (StrUtil.isNotBlank(photoUrls)) ins.setPhotoUrls(photoUrls);
        if (StrUtil.isNotBlank(reportNo)) {
            String remark = (StrUtil.isBlank(ins.getRemark()) ? "" : ins.getRemark() + "; ") + "XK 首检报告号 " + reportNo;
            QmsIqcItemService.requireLength(remark, 255, "备注/报告号");
            ins.setRemark(remark);
        }
        if (inspectionMapper.updateById(ins) != 1) throw BizException.of("检验单已变化，请刷新后重试");
        check(ins.getId(), Constants.RESULT_PASS, "NONE", "XK 首检 (FAI) 合格, 放行之");
        // 把合格快照写回入库单 (供门禁 + XK 展示)
        com.industrial.erp.modules.production.entity.PrdSemiIn semiIn = prdSemiInMapper.selectById(semiInId);
        if (semiIn != null) {
            snapshotFaiGate(semiIn);
            prdSemiInMapper.updateById(semiIn);
        }
        writeAudit("SIGN_PASSED_FAI_FOR_SEMI_IN", 1,
                "semiInId=" + semiInId + " faiId=" + ins.getId() + " reportNo=" + reportNo, null);
        log.info("[QMS-XK] signPassedFaiForSemiIn: semiIn={} fai={} reportNo={}",
                semiInId, ins.getBillNo(), reportNo);
    }

    /**
     * App 端现场签合格 — 成品入库 OQC 版.
     * 找/建该入库单关联的 OQC 草稿, 全合格才放行, check(PASS) 后 PrdFinishedInService.check 的 OQC 软门禁即绿.
     */
    @Transactional(rollbackFor = Exception.class)
    public void signPassedOqcForFinishedIn(Long finishedInId, Long oqcId, String reportNo,
                                           java.math.BigDecimal sampleQty,
                                           String inspectorName, String photoUrls) {
        if (finishedInId == null) throw BizException.of("finishedInId 必填");
        MesQualityInspection ins = (oqcId != null) ? inspectionMapper.selectById(oqcId) : null;
        if (ins == null) ins = findOqcByFinishedInId(finishedInId);
        if (ins == null) {
            // 兜底: 该入库单尚未建 OQC, 现场补一张 OQC 草稿再签合格
            com.industrial.erp.modules.production.entity.PrdFinishedIn fi =
                    prdFinishedInMapper.selectById(finishedInId);
            if (fi == null) throw BizException.of("成品入库单不存在: " + finishedInId);
            requireFoodGradeForInspection(fi.getProductId());
            Long autoOqcId = createOnFinish(fi);
            ins = autoOqcId != null ? inspectionMapper.selectById(autoOqcId) : null;
            if (ins == null) throw BizException.of("无法为该成品入库单建 OQC 检验单");
        }
        if (!finishedInId.equals(ins.getSourceBillId())) throw BizException.of("OQC 与成品入库单不匹配");
        ensureOqcTemplateItems(ins);
        List<QmsIqcItem> oqcItems = iqcItemMapper.selectByIqcId(ins.getId());
        if (oqcItems.isEmpty()) throw BizException.of("OQC 成品检验模板项缺失, 不能放行");
        if (oqcItems.stream().anyMatch(it -> !(isNaFlag(it.getPassFlag())
                || (Integer.valueOf(1).equals(it.getPassFlag()) && StrUtil.isNotBlank(it.getResultValue()))))) {
            throw BizException.of("OQC 成品检验项未逐项实测判定合格（不适用项除外）, 不能现场放行");
        }
        if (sampleQty != null) ins.setSampleQty(sampleQty);
        if (StrUtil.isNotBlank(inspectorName)) ins.setInspectorName(inspectorName);
        if (StrUtil.isNotBlank(photoUrls)) ins.setPhotoUrls(photoUrls);
        if (StrUtil.isNotBlank(reportNo)) {
            ins.setRemark((StrUtil.isBlank(ins.getRemark()) ? "" : ins.getRemark() + "; ")
                    + "XK 成品检报告号 " + reportNo);
        }
        check(ins.getId(), Constants.RESULT_PASS, "NONE", "XK 成品检验 (OQC) 合格, 放行之");
        writeAudit("SIGN_PASSED_OQC_FOR_FINISHED_IN", 1,
                "finishedInId=" + finishedInId + " oqcId=" + ins.getId() + " reportNo=" + reportNo, null);
        log.info("[QMS-XK] signPassedOqcForFinishedIn: fi={} oqc={} reportNo={}",
                finishedInId, ins.getBillNo(), reportNo);
    }


    /**
     * v1.1.101 XK — IQC 详情: head + items + attachments + plan (4 段, App 端 pages/scan/in 用).
     * 替代默认 detail(), 把检验项/附件/计划上下文也回传.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detailForPlan(Long iqcId) {
        Map<String, Object> m = new HashMap<>();
        MesQualityInspection head = inspectionMapper.selectById(iqcId);
        if (head == null) throw BizException.of("检验单不存在");
        m.put("head", head);
        m.put("items", iqcItemMapper.selectByIqcId(iqcId));
        m.put("attachments", iqcAttachmentMapper.selectByIqcId(iqcId));
        // 关联计划上下文 (一期内嵌冗余, 减少 App 端二次请求)
        if (head.getSourceBillId() != null && "PUR_INBOUND_PLAN".equals(head.getSourceBillType())) {
            m.put("plan", inboundPlanMapper.selectById(head.getSourceBillId()));
        }
        return m;
    }

    /**
     * v1.1.101 订单为源 — IQC 详情: head + items + attachments + order.
     * App 端 pages/scan/in (订单版) 用; order 段回传采购订单上下文 (供应商/数量/仓库/单号).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detailForOrder(Long iqcId) {
        Map<String, Object> m = new HashMap<>();
        MesQualityInspection head = inspectionMapper.selectById(iqcId);
        if (head == null) throw BizException.of("检验单不存在");
        m.put("head", head);
        m.put("items", iqcItemMapper.selectByIqcId(iqcId));
        m.put("attachments", iqcAttachmentMapper.selectByIqcId(iqcId));
        if (head.getSourceBillId() != null && "PUR_ORDER".equals(head.getSourceBillType())) {
            com.industrial.erp.modules.purchase.entity.PurOrder ord =
                    orderMapper.selectById(head.getSourceBillId());
            if (ord != null) {
                ord.setDetails(orderDetailMapper.selectByOrderId(ord.getId()));
            }
            m.put("order", ord);
        }
        return m;
    }
}
