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
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
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
import org.springframework.beans.factory.annotation.Autowired;
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
                                 @Lazy com.industrial.erp.modules.production.mapper.PrdFinishedInMapper prdFinishedInMapper) {
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
        MesQualityInspection head = dto.getHead();
        if (head == null) throw BizException.of("检验单头不能为空");
        if (StrUtil.isBlank(head.getInspectionType())) throw BizException.of("检验类型必填");
        if (head.getProductId() == null) throw BizException.of("产品必填");
        head.setBillNo(billNoGenerator.generate(Constants.BILL_QI));
        if (head.getBillDate() == null) head.setBillDate(LocalDate.now());
        head.setInspector(SecurityContext.getUserId());
        head.setInspectorName(SecurityContext.getUsername());
        if (StrUtil.isBlank(head.getStatus())) head.setStatus(Constants.STATUS_DRAFT);
        if (StrUtil.isBlank(head.getResult())) head.setResult(Constants.RESULT_HOLD);
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
        if (StrUtil.isBlank(result)) throw BizException.of("检验结果必填");
        MesQualityInspection rec = inspectionMapper.selectById(id);
        if (rec == null) throw BizException.of("检验单不存在");
        if (Constants.STATUS_CHECKED.equals(rec.getStatus())) {
            throw BizException.of("该检验单已审核, 不可重复操作");
        }
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
        inspectionMapper.updateById(rec);

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
        Page<MesQualityInspection> p = new Page<>(pageNum, pageSize, true);
        LambdaQueryWrapper<MesQualityInspection> w = new LambdaQueryWrapper<>();
        w.eq(StrUtil.isNotBlank(inspectionType), MesQualityInspection::getInspectionType, inspectionType);
        w.eq(StrUtil.isNotBlank(result), MesQualityInspection::getResult, result);
        w.eq(StrUtil.isNotBlank(status), MesQualityInspection::getStatus, status);
        w.orderByDesc(MesQualityInspection::getBillDate)
                .orderByDesc(MesQualityInspection::getId);
        return inspectionMapper.selectPage(p, w);
    }

    /**
     * 检验单详情: head + defects 一起返回 (Map 结构, 便于前端展平).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Map<String, Object> m = new HashMap<>();
        MesQualityInspection head = inspectionMapper.selectById(id);
        if (head == null) throw BizException.of("检验单不存在");
        m.put("head", head);
        m.put("defects", defectMapper.selectByInspectionId(id));
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
        return createInternal(ins);
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
        return createInternal(ins);
    }

    /**
     * 工序报工 → 自动生成 PAT (过程巡检) DRAFT 草稿.
     *
     * <p>v1.1.x PAT 补齐: PAT 是 5 类检验里唯一没有自动触发器的 (IQC/FAI/OQC/OQC_RETURN
     * 都挂到入库/退货流程). PAT 的天然业务挂点 = 扫码报工 (MesReportService.reportWork) —
     * 每道工序报工有合格/不良数、车间、工序名, 记一条 PAT 把「工序过程质量」纳入 QMS 台账.
     * 由上游 MesReportService 在报工末 try/catch 调用, 失败仅记日志<b>不阻塞报工</b>.
     * PAT 不做硬门禁 (过程监控定位), 不合格只在台账留痕 + 可冻结批次.
     *
     * @param p 本次报工的工序实例 (取 workshop/processName/prdOrderNo)
     * @param o 所属生产单 (取 product 上下文), 可空 (空则 product 字段留空)
     * @param good  本次报工合格量 (→ qualifiedQty)
     * @param bad   本次报工不良量 (→ unqualifiedQty)
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOnReport(com.industrial.erp.modules.mes.entity.PrdProcess p,
                               com.industrial.erp.modules.production.entity.PrdOrder o,
                               java.math.BigDecimal good, java.math.BigDecimal bad) {
        if (p == null || p.getId() == null) return null;
        MesQualityInspection ins = new MesQualityInspection();
        ins.setInspectionType(Constants.INSPECTION_PAT);
        ins.setSourceBillType("PRD_PROCESS");
        ins.setSourceBillId(p.getId());
        ins.setSourceBillNo(p.getPrdOrderNo());
        if (o != null) {
            ins.setProductId(o.getProductId());
            ins.setProductCode(o.getProductCode());
            ins.setProductName(o.getProductName());
            ins.setSpec(o.getSpec());
            ins.setBatchNo(o.getBatchNo());
        }
        ins.setWorkshop(p.getWorkshop());  // PAT 专用车间字段, 触发器终于填上了
        java.math.BigDecimal g = good == null ? java.math.BigDecimal.ZERO : good;
        java.math.BigDecimal b = bad == null ? java.math.BigDecimal.ZERO : bad;
        ins.setSampleQty(g.add(b));
        ins.setQualifiedQty(g);
        ins.setUnqualifiedQty(b);
        ins.setBillDate(LocalDate.now());
        ins.setStatus(Constants.STATUS_DRAFT);
        ins.setResult(Constants.RESULT_HOLD);
        ins.setInspector(SecurityContext.getUserId());
        ins.setInspectorName(SecurityContext.getUsername());
        ins.setRemark("工序[" + p.getProcessName() + "] 报工 " + p.getPrdOrderNo()
                + " 自动生成 PAT 巡检待检");
        return createInternal(ins);
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
        return createInternal(ins);
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

    /** 该半成品入库单是否已关联"合格"的 FAI (result=PASS/PASS_DISPOSITION, status=CHECKED). */
    public boolean hasPassedFai(Long semiInId) {
        MesQualityInspection ins = findFaiBySemiInId(semiInId);
        if (ins == null) return false;
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
    }

    /** 把关联 FAI 判定结果/报告号/抽样数/检验人 冗余快照进半成品入库单 (仿 snapshotXkGate). */
    public void snapshotFaiGate(com.industrial.erp.modules.production.entity.PrdSemiIn semiIn) {
        if (semiIn == null || semiIn.getId() == null) return;
        MesQualityInspection ins = findFaiBySemiInId(semiIn.getId());
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

    /** 该成品入库单是否已关联"合格"的 OQC (result=PASS/PASS_DISPOSITION, status=CHECKED). */
    public boolean hasPassedOqc(Long finishedInId) {
        MesQualityInspection ins = findOqcByFinishedInId(finishedInId);
        if (ins == null) return false;
        if (!Constants.STATUS_CHECKED.equals(ins.getStatus())) return false;
        return Constants.RESULT_PASS.equals(ins.getResult())
                || Constants.RESULT_PASS_DISPOSITION.equals(ins.getResult());
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

    /** 通用检验项模板 — 工业级 (4 项). 顺序/编码/单位固定. */
    private static final String[][] DEFAULT_IQC_ITEMS_COMMON = {
            {"PACKAGING",  "包装与标签", "包装完好、名称、批号清晰一致", "目测核对",      null,    "PASS"},
            {"SENSORY",    "外观与感官", "颗粒均匀、无异物、异常气味", "目测/感官",     null,    "PASS"},
            {"DENSITY",    "密度",       "0.918 ± 0.003 g/cm³",        "密度计",        "g/cm³", "PASS"},
            {"MFR",        "熔体质量流动速率", "2.0 ± 0.5 g/10min",   "熔融指数仪",     "g/10min", "PASS"}
    };

    /** 食品级专用检验项模板 (3 项, 仅 is_food_grade=1 商品加载). */
    private static final String[][] DEFAULT_IQC_ITEMS_FOOD = {
            {"FOOD_CONTACT_HEAVY_METAL", "食品接触重金属", "铅≤1mg/kg, 镉≤0.5mg/kg", "ICP-OES", "mg/kg", "PASS"},
            {"SENSORY_ODOR",   "嗅味",       "无异味",                       "嗅觉检测",  null,    "PASS"},
            {"APPEARANCE",     "色泽透明度", "色泽均匀、无杂质",              "目测",      null,    "PASS"}
    };

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
        for (String[] r : tpl) {
            QmsIqcItem it = new QmsIqcItem();
            it.setIqcId(ins.getId());
            it.setLineNo(line++);
            it.setItemCode(r[0]);
            it.setItemName(r[1]);
            it.setRequirement(r[2]);
            it.setMethod(r[3]);
            it.setUnit(r[4]);
            it.setPassFlag(1);  // 默认 PASS, App 端按实情改
            it.setIsFoodGradeItem(isFoodGrade ? 1 : 0);
            it.setSource("TEMPLATE");
            iqcItemMapper.insert(it);
        }
        log.info("[QMS-XK] loadDefaultIqcItems: iqc={} food={} items={}",
                ins.getBillNo(), isFoodGrade, tpl.size());
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
        if (ins == null) {
            // 兜底: 该入库单尚未建 FAI (仓管先建了入库单), 现场补一张 FAI 草稿再签合格
            com.industrial.erp.modules.production.entity.PrdSemiIn si = prdSemiInMapper.selectById(semiInId);
            if (si == null) throw BizException.of("半成品入库单不存在: " + semiInId);
            Long autoFaiId = createOnSemiIn(si);
            ins = autoFaiId != null ? inspectionMapper.selectById(autoFaiId) : null;
            if (ins == null) throw BizException.of("无法为该半成品入库单建 FAI 检验单");
        }
        if (!semiInId.equals(ins.getSourceBillId())) throw BizException.of("FAI 与半成品入库单不匹配");
        if (!allItemsPassedOrEmpty(ins.getId())) {
            throw BizException.of("FAI 存在不合格检验项, 不能现场放行");
        }
        if (sampleQty != null) ins.setSampleQty(sampleQty);
        if (StrUtil.isNotBlank(inspectorName)) ins.setInspectorName(inspectorName);
        if (StrUtil.isNotBlank(photoUrls)) ins.setPhotoUrls(photoUrls);
        if (StrUtil.isNotBlank(reportNo)) {
            ins.setRemark((StrUtil.isBlank(ins.getRemark()) ? "" : ins.getRemark() + "; ")
                    + "XK 首检报告号 " + reportNo);
        }
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
            Long autoOqcId = createOnFinish(fi);
            ins = autoOqcId != null ? inspectionMapper.selectById(autoOqcId) : null;
            if (ins == null) throw BizException.of("无法为该成品入库单建 OQC 检验单");
        }
        if (!finishedInId.equals(ins.getSourceBillId())) throw BizException.of("OQC 与成品入库单不匹配");
        if (!allItemsPassedOrEmpty(ins.getId())) {
            throw BizException.of("OQC 存在不合格检验项, 不能现场放行");
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
