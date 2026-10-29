package com.industrial.erp.modules.mes.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.mes.entity.MesBatch;
import com.industrial.erp.modules.mes.entity.MesBatchLink;
import com.industrial.erp.modules.mes.entity.MesProcessDef;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.MesBatchLinkMapper;
import com.industrial.erp.modules.mes.mapper.MesBatchMapper;
import com.industrial.erp.modules.mes.mapper.MesProcessDefMapper;
import com.industrial.erp.modules.mes.mapper.PrdProcessMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * v1.1.80 MES 第一阶段 — 打开「车间黑箱」。
 *
 * <p>职责 (一期范围, 报工不动库存 / 批次领料时登记):
 * <ol>
 *   <li><b>开单展开工序</b> — {@link #expandProcessesOnRelease} 按产品默认工序链
 *       ({@code mes_process_def.is_default=1}) 展开 N 行 {@code prd_process}, 由
 *       {@code PrdOrderService.release()} 调用。</li>
 *   <li><b>扫码报工</b> — {@link #reportWork} 写 {@code prd_process} 的
 *       合格/不良/操作人/耗时, 驱动订单工序进度。报工只动工序实例, <b>不动 inv_stock</b>。</li>
 *   <li><b>批次登记 + 流向绑定</b> — {@link #registerRawOnIssue} / {@link #registerFgOnFinish}
 *       在「领料」「成品入库」两个边界自动登记 {@code mes_batch} + 写 {@code mes_batch_link}。</li>
 *   <li><b>批次追溯</b> — {@link #batchTrace} 沿 mes_batch_link child→parent 递归上溯
 *       (成品→半成品→原料), parent→child 下探 (原料→客户)。</li>
 * </ol>
 *
 * <p>审计: 报工是高频动作, 沿用 {@code McpAuditService} 教训 —— <b>同步直写</b>
 * {@code SysOperLogMapper.insert} (module=MES报工), 不走 @Async @EventListener (高频会漏写)。
 */
@Service
public class MesReportService {

    private static final Logger log = LoggerFactory.getLogger(MesReportService.class);

    private final PrdProcessMapper procMapper;
    private final MesProcessDefMapper defMapper;
    private final MesBatchMapper batchMapper;
    private final MesBatchLinkMapper linkMapper;
    private final PrdOrderMapper orderMapper;
    private final SysOperLogMapper operLogMapper;
    /** v1.1.99 C3: 工艺参数 (报工越限判定读 isCritical + 上下限). */
    private final com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper paramMapper;
    /** v1.1.x PAT 补齐: 工序报工自动建 PAT 巡检草稿 (mes → qms 单向注入). */
    private final com.industrial.erp.modules.qms.service.QmsInspectionService qmsInspectionService;

    public MesReportService(PrdProcessMapper procMapper, MesProcessDefMapper defMapper,
                            MesBatchMapper batchMapper, MesBatchLinkMapper linkMapper,
                            PrdOrderMapper orderMapper, SysOperLogMapper operLogMapper,
                            com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper paramMapper,
                            com.industrial.erp.modules.qms.service.QmsInspectionService qmsInspectionService) {
        this.procMapper = procMapper;
        this.defMapper = defMapper;
        this.batchMapper = batchMapper;
        this.linkMapper = linkMapper;
        this.orderMapper = orderMapper;
        this.operLogMapper = operLogMapper;
        this.paramMapper = paramMapper;
        this.qmsInspectionService = qmsInspectionService;
    }

    // ==================================================================
    // 1. 开单展开工序
    // ==================================================================

    /**
     * 按产品默认工序链展开 N 行 {@code prd_process} (status=未开工)。
     *
     * <p>由 {@code PrdOrderService.release()} 在开单时调。若产品没有配置默认工序链
     * (mes_process_def.is_default=1 且 product_id 匹配), 不展开任何工序 (零破坏 ——
     * 没配工序定义的存量单照常开工, 只是没有工序实例可报工)。
     *
     * @return 展开的工序实例 id 列表 (可能为空)
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> expandProcessesOnRelease(PrdOrder order) {
        List<MesProcessDef> defs = new ArrayList<>();
        if (order.getProductId() != null) {
            // 产品专属默认链
            defs.addAll(defMapper.selectList(new LambdaQueryWrapper<MesProcessDef>()
                    .eq(MesProcessDef::getProductId, order.getProductId())
                    .eq(MesProcessDef::getIsDefault, 1)
                    .eq(MesProcessDef::getStatus, 1)
                    .orderByAsc(MesProcessDef::getProcessNo)));
        }
        if (defs.isEmpty() && order.getProductId() != null) {
            // 无产品专属 → 回退通用链 (product_id IS NULL, is_default=1)
            defs.addAll(defMapper.selectList(new LambdaQueryWrapper<MesProcessDef>()
                    .isNull(MesProcessDef::getProductId)
                    .eq(MesProcessDef::getIsDefault, 1)
                    .eq(MesProcessDef::getStatus, 1)
                    .orderByAsc(MesProcessDef::getProcessNo)));
        }
        if (defs.isEmpty()) {
            return new ArrayList<>();   // 没配工序定义, 不展开 (零破坏)
        }

        List<Long> ids = new ArrayList<>();
        for (MesProcessDef d : defs) {
            PrdProcess p = new PrdProcess();
            p.setPrdOrderId(order.getId());
            p.setPrdOrderNo(order.getBillNo());
            p.setProcessNo(d.getProcessNo());
            p.setFromDefId(d.getId());
            p.setProcessName(d.getProcessName());
            p.setWorkshop(d.getWorkshop());
            p.setPlanQty(order.getPlanQty());
            // v1.1.90+: snapshot 工序定义版本 (每行独立取, 允许同一订单不同工序版本不一样)
            String ver = d.getVersion() == null || d.getVersion().isBlank() ? "1.0" : d.getVersion();
            p.setProcessParamVersion(ver);
            p.setStatus(Constants.PROC_STATUS_UNSTARTED);
            procMapper.insert(p);
            ids.add(p.getId());
        }
        log.info("[MES] 开单展开工序 orderId={} 工序数={}", order.getId(), ids.size());
        return ids;
    }

    // ==================================================================
    // 2. 扫码报工
    // ==================================================================

    /**
     * 上报某订单某道工序的工量。
     *
     * <p>报工规则: 合格数达 planQty → status=完成 (2); 否则 status=进行中 (1)。
     * 首次报工记 startTime (未开工→进行中), 完成时记 endTime。反报工 (goodQty<0) 回退。
     * <b>不动 inv_stock / inv_ledger</b> (一期裁剪, 半成品在制品账放二期)。
     *
     * <p>v1.1.99 C3: 工艺参数生效 — {@code paramValues} 携带该工序实测工艺参数 (key=paramKey)。
     * 若工序 fromDefId 有关键参数 (isCritical=1) 且配了上下限, 逐参数比对, <b>任一越限 → 该工序本次
     * 不计合格 / 不自动完工</b> (goodQty 计入 loss), 并写审计「参数 X 越限」。无越限维持原逻辑。
     *
     * @param orderId    生产单 id
     * @param processId  工序实例 id (prd_process.id, 扫码定位到单据后选工序得到)
     * @param goodQty    合格数 (可 0)
     * @param badQty     不良数 (可 0)
     * @param operator   操作人 (工人填; 空则取当前登录用户)
     * @param paramValues 工艺参数实测值 (可空, key=paramKey)
     * @return 更新后的工序实例
     */
    @Transactional(rollbackFor = Exception.class)
    public PrdProcess reportWork(Long orderId, Long processId,
                                 BigDecimal goodQty, BigDecimal badQty, String operator,
                                 java.util.Map<String, java.math.BigDecimal> paramValues) {
        PrdProcess p = procMapper.selectById(processId);
        if (p == null || !Objects.equals(p.getPrdOrderId(), orderId)) {
            throw BizException.of("工序不存在或不属于该生产单");
        }
        BigDecimal g = goodQty == null ? BigDecimal.ZERO : goodQty;
        BigDecimal b = badQty == null ? BigDecimal.ZERO : badQty;
        int st = p.getStatus() == null ? Constants.PROC_STATUS_UNSTARTED : p.getStatus();

        if (st == Constants.PROC_STATUS_FINISHED) {
            throw BizException.of("该工序已完成, 不可再报工 (如需反报工走财务红冲)");
        }
        if ((g.signum() > 0 || b.signum() > 0)
                && qmsInspectionService.isBagProcess(p)
                && !qmsInspectionService.hasPassedFaiForProcess(processId)) {
            throw BizException.of("制袋工序首检尚未合格，请先完成该工序首检");
        }

        // v1.1.99 C3: 工艺参数越限判定 — 关键参数越限则本次合格量转不良, 不自动完工
        String paramViolation = judgeCriticalParamViolation(p, paramValues);

        PrdProcess upd = new PrdProcess();
        upd.setId(p.getId());
        if (StrUtil.isNotBlank(paramViolation)) {
            // 关键参数越限: 本次合格数计为 0, 全部转不良 (工序不自动完工), 保留累计不良
            upd.setGoodQty(p.getGoodQty() == null ? BigDecimal.ZERO : p.getGoodQty());
            upd.setLossQty((p.getLossQty() == null ? BigDecimal.ZERO : p.getLossQty())
                    .add(g).add(b));
        } else {
            // 正常路径: 累计报工量
            upd.setGoodQty(p.getGoodQty() == null ? g : p.getGoodQty().add(g));
            upd.setLossQty(p.getLossQty() == null ? b : p.getLossQty().add(b));
        }
        BigDecimal totalGood = upd.getGoodQty();
        // 首次报工记 start; 达 plan 且无越限记 end + 完成
        if (st == Constants.PROC_STATUS_UNSTARTED) {
            upd.setStartTime(LocalDateTime.now());
        }
        boolean finished = StrUtil.isBlank(paramViolation)
                && p.getPlanQty() != null && p.getPlanQty().compareTo(BigDecimal.ZERO) > 0
                && totalGood.compareTo(p.getPlanQty()) >= 0;
        upd.setStatus(finished ? Constants.PROC_STATUS_FINISHED : Constants.PROC_STATUS_IN_PROGRESS);
        if (finished && p.getEndTime() == null) {
            upd.setEndTime(LocalDateTime.now());
        }
        upd.setOperator(StrUtil.isBlank(operator) ? SecurityContext.getUsername() : operator);
        // 回写订单实际/良品量 (聚合该订单所有工序) —— 订单进度看板的数字来源
        syncOrderQtyFromProcesses(orderId);
        procMapper.updateById(upd);
        String what = "报工 " + p.getPrdOrderNo() + " 工序[" + p.getProcessName()
                + "] 合格=" + g + " 不良=" + b + " 操作人=" + upd.getOperator();
        writeAudit("REPORT_WORK", 1, what, null);
        if (StrUtil.isNotBlank(paramViolation)) {
            writeAudit("REPORT_WORK_PARAM_VIOLATION", 0,
                    "工序[" + p.getProcessName() + "] " + paramViolation + " → 本次不计合格/不自动完工", paramViolation);
        }
        PrdProcess reloaded = procMapper.selectById(p.getId());
        log.info("[MES] 报工 orderId={} process={} status={} paramViolation={}",
                orderId, p.getId(), reloaded.getStatus(), paramViolation);
        // v1.1.x PAT 补齐: 工序报工自动建 PAT 巡检草稿 (仿 createOnFinish 触发器模式).
        // 不阻塞报工 — PAT 属过程监控, 失败仅 warn; 越限路径 (paramViolation 非空) 本次不计合格,
        // 但巡检量仍按本次报工的合格/不良记录, 供 QMS 台账追溯.
        try {
            com.industrial.erp.modules.production.entity.PrdOrder o = orderMapper.selectById(orderId);
            qmsInspectionService.createOnReport(p, o, g, b);
        } catch (Exception patEx) {
            log.warn("[MES] 报工 PAT 巡检自动生成失败, 不影响报工: {}", patEx.getMessage());
        }
        return reloaded;
    }

    /**
     * C3 工艺参数越限判定: 该工序 fromDefId 下 isCritical=1 且配了上下限的参数, 逐一比对 paramValues,
     * 任一越限返回「参数 X 越限 (...)」描述; 全部合规 (或无关键参数) 返回 null。
     */
    private String judgeCriticalParamViolation(PrdProcess p, java.util.Map<String, java.math.BigDecimal> paramValues) {
        if (p == null || p.getFromDefId() == null || paramValues == null || paramValues.isEmpty()) return null;
        List<com.industrial.erp.modules.pdm.entity.MesProcessParam> params =
                paramMapper.selectByProcessDefId(p.getFromDefId());
        if (params == null) return null;
        StringBuilder violations = new StringBuilder();
        for (com.industrial.erp.modules.pdm.entity.MesProcessParam par : params) {
            if (par.getIsCritical() == null || par.getIsCritical() != 1) continue;
            java.math.BigDecimal submitted = paramValues.get(par.getParamKey());
            if (submitted == null) continue;   // 未提交该参数 → 不判 (只判已采集的)
            if (par.getUpperLimit() != null && submitted.compareTo(par.getUpperLimit()) > 0) {
                if (violations.length() > 0) violations.append("; ");
                violations.append(par.getParamKey()).append(" 超上限(").append(par.getUpperLimit()).append(")");
            }
            if (par.getLowerLimit() != null && submitted.compareTo(par.getLowerLimit()) < 0) {
                if (violations.length() > 0) violations.append("; ");
                violations.append(par.getParamKey()).append(" 低于下限(").append(par.getLowerLimit()).append(")");
            }
        }
        return violations.length() > 0 ? violations.toString() : null;
    }

    /** 旧签名兼容重载 (无工艺参数), 委托到 6 参版. App/PC 老调用点 0 改动. */
    @Transactional(rollbackFor = Exception.class)
    public PrdProcess reportWork(Long orderId, Long processId,
                                 BigDecimal goodQty, BigDecimal badQty, String operator) {
        return reportWork(orderId, processId, goodQty, badQty, operator, null);
    }

    /** 订单 actualQty/goodQty/lossQty = 该订单全部工序实例之和 (进度看板数字来源). */
    private void syncOrderQtyFromProcesses(Long orderId) {
        List<PrdProcess> all = procMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, orderId));
        BigDecimal totalGood = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        for (PrdProcess x : all) {
            if (x.getGoodQty() != null) totalGood = totalGood.add(x.getGoodQty());
            if (x.getLossQty() != null) totalLoss = totalLoss.add(x.getLossQty());
        }
        PrdOrder upd = new PrdOrder();
        upd.setId(orderId);
        upd.setGoodQty(totalGood);
        upd.setLossQty(totalLoss);
        upd.setActualQty(totalGood.add(totalLoss));
        orderMapper.updateById(upd);
    }

    /**
     * 订单工序进度: 该订单全部工序实例按 process_no 排序 (实时看板数据源)。
     */
    @Transactional(readOnly = true)
    public List<PrdProcess> orderProgress(Long orderId) {
        return procMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, orderId)
                .orderByAsc(PrdProcess::getProcessNo));
    }

    // ==================================================================
    // 5. 工单状态机门禁 (v1.1.99 C1: 工序全完工才允许完工)
    // ==================================================================

    /**
     * 该订单是否「全部工序已完工」(status=FINISHED)。
     *
     * <p>工单完工门禁 (PrdOrderService.finish 硬门禁): 只有配置了工序链 (开单展开过
     * prd_process) 且每道工序都达量完工时返回 true。
     * <b>关键: 该订单没有任何工序实例 (没配工序定义的存量单) → 返回 true</b>, 不阻塞老流程 —
     * 门禁只对「配置了工序链」的工单生效。
     */
    @Transactional(readOnly = true)
    public boolean allProcessesFinished(Long orderId) {
        if (orderId == null) return true;
        List<PrdProcess> all = procMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, orderId));
        if (all == null || all.isEmpty()) return true;   // 没配工序链 → 不拦
        for (PrdProcess p : all) {
            if (!Integer.valueOf(Constants.PROC_STATUS_FINISHED).equals(p.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /** 该订单未完工的工序名列表 (供报错文案; 全完工或无工序时返回空)。 */
    @Transactional(readOnly = true)
    public List<String> listUnfinishedProcessNames(Long orderId) {
        List<String> names = new ArrayList<>();
        if (orderId == null) return names;
        List<PrdProcess> all = procMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, orderId));
        if (all == null) return names;
        for (PrdProcess p : all) {
            if (!Integer.valueOf(Constants.PROC_STATUS_FINISHED).equals(p.getStatus())) {
                names.add(StrUtil.isBlank(p.getProcessName()) ? ("工序#" + p.getProcessNo()) : p.getProcessName());
            }
        }
        return names;
    }

    // ==================================================================
    // 3. 批次登记 + 流向绑定 (领料 / 成品入库 两边界)
    // ==================================================================

    /**
     * 领料时登记 RAW 原料批次 (用户决策「领料时才登记」)。
     *
     * <p>对领料单的每个明细 batch_no, 幂等登记 {@code mes_batch}(RAW, by batch_no),
     * 并写 {@code mes_batch_link} (child=半成品/成品批, parent=该原料批, bill_type=ISSUE)。
     * 不碰采购入库 (批次继续走 inv_stock 自由文本)。
     *
     * @param rawBatchNo   原料批次号 (领料明细 batch_no)
     * @param childBatchNo 流入的半成品/成品批 (可空 = 尚未产生下游)
     * @param qty          本次领用数量
     * @param prdOrderId   生产单 id
     * @param billId       领料单 id
     * @param billNo       领料单号
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerRawOnIssue(String rawBatchNo, String childBatchNo,
                                   BigDecimal qty, Long prdOrderId, Long billId, String billNo) {
        if (StrUtil.isBlank(rawBatchNo)) return;   // 无批次号的领料不登记
        MesBatch raw = new MesBatch();
        raw.setBatchNo(rawBatchNo);
        raw.setBatchType(Constants.BATCH_TYPE_RAW);
        raw.setStatus("ACTIVE");
        raw.setQty(qty);
        upsertBatch(raw);
        if (StrUtil.isNotBlank(childBatchNo)) {
            MesBatchLink link = new MesBatchLink();
            link.setChildBatchNo(childBatchNo);
            link.setParentBatchNo(rawBatchNo);
            link.setQty(qty);
            link.setPrdOrderId(prdOrderId);
            link.setBillType("ISSUE");
            link.setBillId(billId);
            link.setBillNo(billNo);
            linkMapper.insert(link);
        }
        log.info("[MES] 领料登记 RAW 批次 {} → {} qty={}", rawBatchNo, childBatchNo, qty);
    }

    /**
     * v1.1.132 XK 开工投料 — 原料批号是否已在 {@code mes_batch} 登记过。
     *
     * <p>开工确认接口用它回一份「未登记批号」清单给 App 提示 (操作工手录的常是供应商
     * 桶号/袋号, 与来料检验登记的批次对不上号), 只提示不否决开工。
     */
    @Transactional(readOnly = true)
    public boolean rawBatchKnown(String batchNo) {
        if (StrUtil.isBlank(batchNo)) return false;
        MesBatch exist = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, batchNo.trim()).last("LIMIT 1"));
        return exist != null;
    }

    /**
     * 成品入库时登记 FG 成品批次 + 流向 (child=成品批, parent=半成品卷批 可空)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerFgOnFinish(String fgBatchNo, Long prdOrderId, String prdOrderNo,
                                   String customerBillNo, Long customerId,
                                   String semiBatchNo, BigDecimal qty) {
        if (StrUtil.isBlank(fgBatchNo)) return;
        MesBatch fg = new MesBatch();
        fg.setBatchNo(fgBatchNo);
        fg.setBatchType(Constants.BATCH_TYPE_FG);
        fg.setSourcePrdOrderNo(prdOrderNo);
        fg.setStatus("ACTIVE");
        fg.setQty(qty);
        upsertBatch(fg);
        if (StrUtil.isNotBlank(semiBatchNo)) {
            MesBatchLink link = new MesBatchLink();
            link.setChildBatchNo(fgBatchNo);
            link.setParentBatchNo(semiBatchNo);
            link.setQty(qty);
            link.setPrdOrderId(prdOrderId);
            link.setBillType("FG_IN");
            link.setBillNo(customerBillNo);
            link.setCustomerId(customerId);
            linkMapper.insert(link);
        }
        log.info("[MES] 成品入库登记 FG 批次 {} ← semi={} qty={}", fgBatchNo, semiBatchNo, qty);
    }

    /**
     * v1.1.98 半成品入库时登记 SEMI 半成品卷批次 + 流向 (child=半成品批, parent=原料批 可空).
     * 仿 {@link #registerFgOnFinish}, 打通 原料批 → 半成品卷批 追溯.
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerSemiOnFinish(String semiBatchNo, Long prdOrderId, String prdOrderNo,
                                     String billNo, String rawBatchNo, BigDecimal qty) {
        if (StrUtil.isBlank(semiBatchNo)) return;
        MesBatch semi = new MesBatch();
        semi.setBatchNo(semiBatchNo);
        semi.setBatchType(Constants.BATCH_TYPE_SEMI);
        semi.setSourcePrdOrderNo(prdOrderNo);
        semi.setStatus("ACTIVE");
        semi.setQty(qty);
        upsertBatch(semi);
        if (StrUtil.isNotBlank(rawBatchNo)) {
            MesBatchLink link = new MesBatchLink();
            link.setChildBatchNo(semiBatchNo);
            link.setParentBatchNo(rawBatchNo);
            link.setQty(qty);
            link.setPrdOrderId(prdOrderId);
            link.setBillType("SEMI_IN");
            link.setBillNo(billNo);
            linkMapper.insert(link);
        }
        log.info("[MES] 半成品入库登记 SEMI 批次 {} ← raw={} qty={}", semiBatchNo, rawBatchNo, qty);
    }

    /** 幂等登记批次 (batch_no+deleted 唯一, 存在则补类型/不覆盖)。 */
    private void upsertBatch(MesBatch b) {
        MesBatch exist = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, b.getBatchNo()).last("LIMIT 1"));
        if (exist != null) {
            // 已有批次, 只补类型 (若之前登记为空); 不覆盖来源字段
            if (StrUtil.isBlank(exist.getBatchType()) && StrUtil.isNotBlank(b.getBatchType())) {
                MesBatch u = new MesBatch();
                u.setId(exist.getId());
                u.setBatchType(b.getBatchType());
                u.setQty(b.getQty());
                batchMapper.updateById(u);
            }
            return;
        }
        b.setCreateBy(SecurityContext.getUserId());
        batchMapper.insert(b);
    }

    // ==================================================================
    // 4. 批次追溯 (递归 up/down)
    // ==================================================================

    /** 追溯结果: 批次 + 它的直接上游/下游链路。 */
    public static class TraceResult {
        public MesBatch batch;
        public List<MesBatchLink> upstream = new ArrayList<>();   // 我用了哪些 (parent→me)
        public List<MesBatchLink> downstream = new ArrayList<>(); // 我流向哪些 (me→child)
    }

    /**
     * 单批次追溯: 该批次的直接上游 (我用了哪些料) + 直接下游 (我流向哪)。
     * 前端拿到后可逐层递归调本方法展开完整 料→半成品→成品→客户 链。
     * 设深度上限防脏环 (递归到 MAX_DEPTH 停止)。
     */
    @Transactional(readOnly = true)
    public TraceResult batchTrace(String batchNo, int depth) {
        TraceResult r = new TraceResult();
        if (StrUtil.isBlank(batchNo)) return r;
        if (depth > 5) return r;   // 脏环防护: 最多递归 5 层
        MesBatch b = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, batchNo).last("LIMIT 1"));
        r.batch = b;
        // 上游: 我 (作为 child) 用了哪些 parent
        r.upstream.addAll(linkMapper.selectList(new LambdaQueryWrapper<MesBatchLink>()
                .eq(MesBatchLink::getChildBatchNo, batchNo)));
        // 下游: 我 (作为 parent) 流向了哪些 child
        r.downstream.addAll(linkMapper.selectList(new LambdaQueryWrapper<MesBatchLink>()
                .eq(MesBatchLink::getParentBatchNo, batchNo)));
        return r;
    }

    // ==================================================================
    // 5.5 v1.1.99 C2 — 半成品→原料 上游取值 + 全链递归追溯
    // ==================================================================

    /**
     * 取某生产单领料时登记的「原料批号」(mes_batch_link 中该 prd_order_id 的 ISSUE 行 parent_batch_no, 最新一条).
     * 供 {@link #registerSemiOnFinish} 补 SEMI 批的 parent (打通 半成品→原料 上游断链). 无则返回 null (零破坏).
     */
    @Transactional(readOnly = true)
    public String resolveRawBatchForOrder(Long prdOrderId) {
        if (prdOrderId == null) return null;
        MesBatchLink link = linkMapper.selectOne(new LambdaQueryWrapper<MesBatchLink>()
                .eq(MesBatchLink::getPrdOrderId, prdOrderId)
                .eq(MesBatchLink::getBillType, "ISSUE")
                .isNotNull(MesBatchLink::getParentBatchNo)
                .orderByDesc(MesBatchLink::getId)
                .last("LIMIT 1"));
        if (link == null || StrUtil.isBlank(link.getParentBatchNo())) return null;
        return link.getParentBatchNo();
    }

    /** 全链追溯结果 (可直接渲染的分层时间线): 从 batchNo 出发的 上游(料→半成品→成品 上溯) + 下游(成品→客户 下探). */
    public static class FullTrace {
        public String batchNo;
        public MesBatch batch;
        /** 上游链路 (我用了哪些), 深度递增: 0=直接上游. */
        public List<MesBatchLink> upstream = new ArrayList<>();
        /** 下游链路 (我流向哪些, 含客户), 深度递增. */
        public List<MesBatchLink> downstream = new ArrayList<>();
        /** 上溯到的全部批次号 (料→半成品→成品 方向), 去重. */
        public List<String> upstreamBatches = new ArrayList<>();
        /** 下探到的全部批次号 + 客户, 去重. */
        public List<String> downstreamBatches = new ArrayList<>();
        /** 该批次最终流向的客户 id (经下游 link 找到 customer_id 非空则填, 否则 null). */
        public Long customer;
        public String customerName;
    }

    /**
     * 服务端全链递归追溯 (v1.1.99 C2): 从 batchNo 双向 BFS 展开 料→半成品→成品→客户.
     * 上游 = 我 (child) 的 parent 链; 下游 = 我 (parent) 的 child 链 (直到命中 customer_id).
     * 深度上限 8 + 已访问集合 防脏环. 一次返回整棵链, 前端不用再逐层调 batchTrace.
     */
    @Transactional(readOnly = true)
    public FullTrace traceFull(String batchNo) {
        FullTrace r = new FullTrace();
        r.batchNo = batchNo;
        if (StrUtil.isBlank(batchNo)) return r;
        r.batch = batchMapper.selectOne(new LambdaQueryWrapper<MesBatch>()
                .eq(MesBatch::getBatchNo, batchNo).last("LIMIT 1"));

        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(batchNo);
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(batchNo);
        int depth = 0;
        int maxDepth = 8;
        // 上游 BFS (child→parent): 当前层批次作为 child, 找它们的 parent (我用的料)
        while (!queue.isEmpty() && depth < maxDepth) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String cur = queue.poll();
                for (MesBatchLink l : linkMapper.selectList(new LambdaQueryWrapper<MesBatchLink>()
                        .eq(MesBatchLink::getChildBatchNo, cur))) {
                    r.upstream.add(l);
                    String pn = l.getParentBatchNo();
                    if (StrUtil.isNotBlank(pn) && seen.add(pn)) queue.add(pn);
                }
            }
            depth++;
        }
        // 上溯批号 (parent 集合)
        r.upstreamBatches = r.upstream.stream().map(MesBatchLink::getParentBatchNo)
                .filter(StrUtil::isNotBlank).distinct().collect(java.util.stream.Collectors.toList());

        // 下游 BFS (parent→child), 顺带抓客户
        seen.clear();
        seen.add(batchNo);
        queue.add(batchNo);
        depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String cur = queue.poll();
                for (MesBatchLink l : linkMapper.selectList(new LambdaQueryWrapper<MesBatchLink>()
                        .eq(MesBatchLink::getParentBatchNo, cur))) {
                    r.downstream.add(l);
                    if (l.getCustomerId() != null && r.customer == null) {
                        r.customer = l.getCustomerId();
                    }
                    String cn = l.getChildBatchNo();
                    if (StrUtil.isNotBlank(cn) && seen.add(cn)) queue.add(cn);
                }
            }
            depth++;
        }
        r.downstreamBatches = r.downstream.stream().map(MesBatchLink::getChildBatchNo)
                .filter(StrUtil::isNotBlank).distinct().collect(java.util.stream.Collectors.toList());
        return r;
    }

    // ==================================================================
    // 6. 工序定义 CRUD (PC 端配工序链用)
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Long saveProcessDef(MesProcessDef def) {
        if (StrUtil.isBlank(def.getProcessName())) throw BizException.of("工序名必填");
        if (def.getProcessNo() == null) def.setProcessNo(0);
        if (def.getIsDefault() == null) def.setIsDefault(0);
        if (def.getStatus() == null) def.setStatus(1);
        def.setCreateBy(SecurityContext.getUserId());
        if (def.getId() == null) {
            defMapper.insert(def);
        } else {
            def.setUpdateBy(SecurityContext.getUserId());
            defMapper.updateById(def);
        }
        writeAudit("SAVE_PROCESS_DEF", 1, "工序定义 " + def.getProcessName() + " id=" + def.getId(), null);
        return def.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDef(Long id) {
        MesProcessDef d = defMapper.selectById(id);
        if (d != null) {
            defMapper.deleteById(id);
            writeAudit("DEL_PROCESS_DEF", 1, "删工序定义 id=" + id, null);
        }
    }

    /** 某产品(可空=通用)的默认工序链, 按 process_no 排序。 */
    @Transactional(readOnly = true)
    public List<MesProcessDef> listProcessDef(Long productId) {
        return defMapper.selectList(new LambdaQueryWrapper<MesProcessDef>()
                .eq(productId != null, MesProcessDef::getProductId, productId)
                .isNull(productId == null, MesProcessDef::getProductId)
                .eq(MesProcessDef::getIsDefault, 1)
                .eq(MesProcessDef::getStatus, 1)
                .orderByAsc(MesProcessDef::getProcessNo));
    }

    /** 批次列表 (按类型/产品筛, 最近 200 条)。 */
    @Transactional(readOnly = true)
    public List<MesBatch> listBatches(String batchType, Long productId) {
        return batchMapper.selectList(new LambdaQueryWrapper<MesBatch>()
                .eq(StrUtil.isNotBlank(batchType), MesBatch::getBatchType, batchType)
                .eq(productId != null, MesBatch::getProductId, productId)
                .orderByDesc(MesBatch::getCreateTime)
                .last("LIMIT 200"));
    }

    /**
     * v1.1.99 C2: 销售出库 (FG 批次流向客户) — 打通「料→半成品→成品→客户」追溯出口.
     *
     * <p>销售出库审核时, 每个有 batchNo 的明细行写一条 mes_batch_link
     * (billType=FG_OUT, child=FG 批, customerId=出库客户), 使 FG 批次能下探到最终客户.
     * FG 批次本身幂等登记 (SEMI/FG 上游由 registerSemiOnFinish/registerFgOnFinish 已建).
     *
     * @param fgBatchNo  成品批次号 (明细 batch_no; 空则跳过)
     * @param customerId 出库客户 (追溯出口)
     * @param qty        本次出库数量
     * @param prdOrderId 关联生产单 (可空, 便于按单上溯)
     * @param billNo     出库单号 (留痕)
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerFgOutToCustomer(String fgBatchNo, Long customerId,
                                         BigDecimal qty, Long prdOrderId, String billNo) {
        if (StrUtil.isBlank(fgBatchNo)) return;   // 无批次号的出库行不登记
        // FG 批次幂等 (若成品入库已登记则跳过主表, 只补流向)
        MesBatch fg = new MesBatch();
        fg.setBatchNo(fgBatchNo);
        fg.setBatchType(Constants.BATCH_TYPE_FG);
        fg.setQty(qty);
        fg.setStatus("ACTIVE");
        upsertBatch(fg);
        MesBatchLink link = new MesBatchLink();
        link.setChildBatchNo(fgBatchNo);
        link.setParentBatchNo(null);   // 成品→客户 出口, 无更下游父批
        link.setQty(qty);
        link.setPrdOrderId(prdOrderId);
        link.setBillType("FG_OUT");
        link.setBillNo(billNo);
        link.setCustomerId(customerId);
        linkMapper.insert(link);
        log.info("[MES] 销售出库登记 FG 批次→客户 {} → customer={} qty={}", fgBatchNo, customerId, qty);
    }

    // ==================================================================
    // 审计 (同步直写, McpAuditService 教训)
    // ==================================================================

    private void writeAudit(String method, int status, String what, String errMsg) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("MES报工");
            l.setBusinessType("MES");
            l.setMethod("[MES] " + method);
            l.setRequestMethod("POST");
            l.setRequestParam(what);
            if (errMsg != null) l.setErrorMsg(StrUtil.maxLength(errMsg, 1000));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(status);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
            log.info("[MES] audit ok method={} status={}", method, status);
        } catch (Exception e) {
            log.warn("[MES] 审计写入失败 (不阻塞主流程): {}", e.getMessage());
        }
    }
}
