package com.industrial.erp.modules.mes.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.mes.dto.WorkStartConfirmDTO;
import com.industrial.erp.modules.mes.entity.MesBatchLink;
import com.industrial.erp.modules.mes.entity.MesWorkStartConfirm;
import com.industrial.erp.modules.mes.entity.PrdProcess;
import com.industrial.erp.modules.mes.mapper.MesBatchLinkMapper;
import com.industrial.erp.modules.mes.mapper.MesWorkStartConfirmMapper;
import com.industrial.erp.modules.mes.mapper.PrdProcessMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.entity.PrdSemiIn;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionMapper;
import com.industrial.erp.modules.production.mapper.PrdSemiInMapper;
import com.industrial.erp.modules.production.service.PrdOrderService;
import com.industrial.erp.modules.xk.entity.XkEnvCheck;
import com.industrial.erp.modules.xk.mapper.XkEnvCheckMapper;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1.1.132 XK16-204 — 现场开工确认 (生产环境 + 投料原料批号 + 批号照片).
 *
 * <p>XK16-204 细则要求生产过程记录可追溯: 何时开工、谁开工、开工前生产环境是否合格、
 * 投入的是哪批原料. App 车间「开工」按钮原先只调 {@link PrdOrderService#release},
 * 三样都不留痕. 本服务把它们绑成一次原子操作:
 *
 * <ol>
 *   <li><b>环境门禁</b> — 未确认合格直接拒绝开工 (不合格没有落库路径).</li>
 *   <li><b>2 份环境检查记录</b> — 自动写 {@code xk_env_check} 并置 CHECKED
 *       (车间按工序区分: 吹膜开工→吹膜车间, 制袋开工→制袋车间; 检查项 = 洁净度 + 温湿度).</li>
 *   <li><b>投料批号绑定</b> — 吹膜开工登记 {@code mes_batch}(RAW) + 写 {@code mes_batch_link}
 *       (billType=ISSUE, parent=原料批), 使「这卷膜用了哪批料」在上游可解析
 *       ({@code MesReportService.resolveRawBatchForOrder} 正是按该组合取数).</li>
 *   <li><b>留痕</b> — {@code mes_work_start_confirm} 一行 + {@code xk_audit_log} 同步直写.</li>
 * </ol>
 *
 * <p>制袋开工附带「领用该生产单号的半成品」: 按该单已审核的 {@code prd_semi_in} 生成领料单
 * (DRAFT, 扣除已被历史领料单领过的批次; 领料单开在**半成品入库所在仓**, 跨仓拆单).
 * 与 {@code release()} 同口径 —— 开工不替仓管做库存账,
 * 库存移动仍需「领料管理」审核, 避免把 制袋工 无权出库的库存动作塞进开工事务.
 */
@Service
public class MesWorkStartService {

    private static final Logger log = LoggerFactory.getLogger(MesWorkStartService.class);

    public static final String STAGE_BLOW = "BLOW";
    public static final String STAGE_BAG = "BAG";

    /** 开工前必查的 2 项生产环境检查 (code → 中文名), 与 PC「生产环境检查」下拉取值一致. */
    private static final String[][] ENV_CHECKS = {
            {"CLEANLINESS", "洁净度 / 环境卫生"},
            {"TEMP_HUMIDITY", "温湿度"},
    };

    private final PrdOrderMapper orderMapper;
    private final PrdProcessMapper procMapper;
    private final MesBatchLinkMapper linkMapper;
    private final MesWorkStartConfirmMapper confirmMapper;
    private final XkEnvCheckMapper envMapper;
    private final PrdSemiInMapper semiInMapper;
    private final PrdRequisitionMapper reqMapper;
    private final PrdRequisitionDetailMapper reqDetailMapper;
    private final MesReportService mesReportService;
    private final PrdOrderService prdOrderService;
    private final XkAuditService auditService;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;

    public MesWorkStartService(PrdOrderMapper orderMapper, PrdProcessMapper procMapper,
                               MesBatchLinkMapper linkMapper, MesWorkStartConfirmMapper confirmMapper,
                               XkEnvCheckMapper envMapper, PrdSemiInMapper semiInMapper,
                               PrdRequisitionMapper reqMapper, PrdRequisitionDetailMapper reqDetailMapper,
                               MesReportService mesReportService, PrdOrderService prdOrderService,
                               XkAuditService auditService, BillNoGenerator billNoGenerator,
                               PermissionService permService) {
        this.orderMapper = orderMapper;
        this.procMapper = procMapper;
        this.linkMapper = linkMapper;
        this.confirmMapper = confirmMapper;
        this.envMapper = envMapper;
        this.semiInMapper = semiInMapper;
        this.reqMapper = reqMapper;
        this.reqDetailMapper = reqDetailMapper;
        this.mesReportService = mesReportService;
        this.prdOrderService = prdOrderService;
        this.auditService = auditService;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
    }

    // ==================================================================
    // 1. 吹膜开工
    // ==================================================================

    /**
     * 吹膜开工: 环境确认 → 2 份环境检查记录 → 投料批号+照片落库 → 生产单开工 (release).
     *
     * <p>批号/照片缺失或环境不合格一律抛错 (整事务回滚, 不留半成品状态).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmBlowStart(Long orderId, WorkStartConfirmDTO dto) {
        PrdOrder order = requireOrder(orderId);
        requireEnvQualified(dto);
        List<String> batchNos = normalize(dto.getRawBatchNos());
        if (batchNos.isEmpty()) {
            throw BizException.of("请填写本次投入的原料批号 (XK16-204 要求投料记录可追溯)");
        }
        List<String> photos = normalize(dto.getPhotoUrls());
        if (photos.isEmpty()) {
            throw BizException.of("请拍摄原料批号标识照片 (至少 1 张)");
        }

        List<Long> envIds = writeEnvChecks(order, STAGE_BLOW, photos);
        // 先开工 (展开工序链 + 领料单), 再回填批号流向 —— 工序实例此时才存在
        prdOrderService.release(orderId);
        Long processId = findProcessId(orderId, "吹膜");
        startProcess(processId);

        MesWorkStartConfirm rec = newConfirm(order, STAGE_BLOW, processId, dto);
        rec.setEnvCheckIds(joinIds(envIds));
        rec.setRawBatchNos(String.join(",", batchNos));
        rec.setPhotoUrls(toJsonArray(photos));
        confirmMapper.insert(rec);

        // 「是否已登记」必须在绑定之前判断 —— bindRawBatch 会把批号 upsert 进 mes_batch,
        // 之后再查就永远是"已登记", 提示失效。
        List<String> unknown = new ArrayList<>();
        for (String batchNo : batchNos) {
            if (!mesReportService.rawBatchKnown(batchNo)) {
                unknown.add(batchNo);
            }
        }
        for (String batchNo : batchNos) {
            bindRawBatch(batchNo, order, processId, rec);
        }

        auditService.writeAudit("XK_WORK_START", rec.getConfirmNo(), "BLOW_START",
                "生产单=" + order.getBillNo() + " 车间=" + rec.getWorkshop()
                        + " 投料批号=" + rec.getRawBatchNos()
                        + " 照片=" + photos.size() + " 张"
                        + " 环境检查=" + rec.getEnvCheckIds(),
                null, null, null);
        log.info("[XK] 吹膜开工确认 confirmNo={} order={} batches={} photos={} envChecks={}",
                rec.getConfirmNo(), order.getBillNo(), batchNos, photos.size(), envIds);

        return result(rec, unknown);
    }

    // ==================================================================
    // 2. 制袋开工
    // ==================================================================

    /**
     * 制袋开工: 环境确认 → 2 份环境检查记录 → 工序实例转「进行中」→ 领用该单可用半成品.
     *
     * <p>工序未展开 (无 BOM 的存量单) 时仍放行环境确认落库, 只是没有工序可置进行中 ——
     * 与 {@code release()} 的「零破坏」口径一致.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmBagStart(Long orderId, WorkStartConfirmDTO dto) {
        PrdOrder order = requireOrder(orderId);
        requireEnvQualified(dto);
        PrdProcess process = requireBagProcess(orderId, dto.getProcessId());
        if (process.getStatus() != null && process.getStatus() != Constants.PROC_STATUS_UNSTARTED) {
            throw BizException.of("制袋工序「" + process.getProcessName() + "」已开工, 请直接报工");
        }

        List<Long> envIds = writeEnvChecks(order, STAGE_BAG, Collections.emptyList());
        startProcess(process.getId());

        MesWorkStartConfirm rec = newConfirm(order, STAGE_BAG, process.getId(), dto);
        rec.setEnvCheckIds(joinIds(envIds));

        List<PrdRequisition> reqs = buildSemiRequisitions(order, rec);
        if (!reqs.isEmpty()) {
            PrdRequisition first = reqs.get(0);
            rec.setRequisitionId(first.getId());
            rec.setRequisitionNo(first.getBillNo());
            if (reqs.size() > 1) {
                // 台账 requisition_no 只有 32 位, 装不下一串单号; 每张领料单自己的备注都回指开工确认号, 不丢追溯
                String extra = reqs.stream().skip(1).map(PrdRequisition::getBillNo)
                        .collect(Collectors.joining(","));
                rec.setRemark(StrUtil.maxLength(
                        StrUtil.nullToEmpty(rec.getRemark()) + " 同批跨仓拆单: " + extra, 512));
            }
        }
        confirmMapper.insert(rec);

        auditService.writeAudit("XK_WORK_START", rec.getConfirmNo(), "BAG_START",
                "生产单=" + order.getBillNo() + " 车间=" + rec.getWorkshop()
                        + " 工序=" + process.getProcessName()
                        + " 环境检查=" + rec.getEnvCheckIds()
                        + (rec.getRequisitionNo() == null ? "" : " 领料单=" + rec.getRequisitionNo()),
                null, null, null);
        log.info("[XK] 制袋开工确认 confirmNo={} order={} process={} requisition={}",
                rec.getConfirmNo(), order.getBillNo(), process.getProcessName(), rec.getRequisitionNo());

        return result(rec, Collections.emptyList());
    }

    // ==================================================================
    // 3. 读接口
    // ==================================================================

    /** 某生产单的开工确认记录 (倒序), 供 App/PC 回显批号与照片. */
    @Transactional(readOnly = true)
    public List<MesWorkStartConfirm> listByOrder(Long orderId) {
        if (orderId == null) return Collections.emptyList();
        return confirmMapper.selectList(new LambdaQueryWrapper<MesWorkStartConfirm>()
                .eq(MesWorkStartConfirm::getPrdOrderId, orderId)
                .orderByDesc(MesWorkStartConfirm::getId));
    }

    // ==================================================================
    // 4. 内部件
    // ==================================================================

    private PrdOrder requireOrder(Long orderId) {
        if (orderId == null) throw BizException.of("生产单 id 必填");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在: " + orderId);
        return order;
    }

    private void requireEnvQualified(WorkStartConfirmDTO dto) {
        if (dto == null || !Boolean.TRUE.equals(dto.getEnvQualified())) {
            throw BizException.of("生产环境未确认合格, 禁止开工 (XK16-204: 开工前须做生产环境检查)");
        }
        permService.requireAnyPerm("production:order:release", "production:report:add", "production:report:list");
    }

    private PrdProcess requireBagProcess(Long orderId, Long processId) {
        if (processId != null) {
            PrdProcess p = procMapper.selectById(processId);
            if (p == null || !orderId.equals(p.getPrdOrderId())) {
                throw BizException.of("工序不存在或不属于该生产单");
            }
            return p;
        }
        List<PrdProcess> bag = procMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, orderId)
                .eq(PrdProcess::getDeleted, 0)
                .like(PrdProcess::getProcessName, "制袋")
                .orderByAsc(PrdProcess::getProcessNo));
        if (bag.isEmpty()) {
            throw BizException.of("该生产单没有制袋工序, 请先在生产单配置工序链或到「工序定义」维护");
        }
        return bag.get(0);
    }

    private Long findProcessId(Long orderId, String nameLike) {
        List<PrdProcess> list = procMapper.selectList(new LambdaQueryWrapper<PrdProcess>()
                .eq(PrdProcess::getPrdOrderId, orderId)
                .eq(PrdProcess::getDeleted, 0)
                .like(PrdProcess::getProcessName, nameLike)
                .orderByAsc(PrdProcess::getProcessNo));
        return list.isEmpty() ? null : list.get(0).getId();
    }

    /**
     * 开工确认通过后把工序实例置「进行中」(status 0 → 1, 记开工时间与开工人).
     *
     * <p>已开工/已完成的工序不动 (幂等); 工序不存在 (无 BOM 工序链的存量单) 时静默跳过,
     * 与 {@code release()} 的零破坏口径一致.
     */
    private void startProcess(Long processId) {
        if (processId == null) {
            return;
        }
        PrdProcess p = procMapper.selectById(processId);
        if (p == null || (p.getStatus() != null && p.getStatus() != Constants.PROC_STATUS_UNSTARTED)) {
            return;
        }
        PrdProcess upd = new PrdProcess();
        upd.setId(processId);
        upd.setStatus(Constants.PROC_STATUS_IN_PROGRESS);
        upd.setStartTime(LocalDateTime.now());
        upd.setOperator(StrUtil.blankToDefault(p.getOperator(), SecurityContext.getUsername()));
        procMapper.updateById(upd);
    }

    private MesWorkStartConfirm newConfirm(PrdOrder order, String stage, Long processId, WorkStartConfirmDTO dto) {
        MesWorkStartConfirm rec = new MesWorkStartConfirm();
        rec.setConfirmNo(billNoGenerator.generate("WS"));
        rec.setPrdOrderId(order.getId());
        rec.setPrdOrderNo(order.getBillNo());
        rec.setStage(stage);
        rec.setWorkshop(workshopOf(stage));
        rec.setPrdProcessId(processId);
        rec.setEnvQualified(1);
        rec.setConfirmBy(SecurityContext.getUserId());
        rec.setConfirmName(SecurityContext.getUsername());
        rec.setConfirmTime(LocalDateTime.now());
        rec.setRemark(StrUtil.maxLength(dto == null ? null : dto.getRemark(), 512));
        return rec;
    }

    /** 车间名按工序区分 (用户口径: 吹膜开工→吹膜车间记录, 制袋开工→制袋车间记录). */
    private String workshopOf(String stage) {
        return STAGE_BAG.equals(stage) ? "制袋车间" : "吹膜车间";
    }

    /**
     * 生成 2 份合格的生产环境检查记录 (洁净度 + 温湿度), 直接落 CHECKED 并同步写 XK 审计.
     *
     * <p>绕开 {@code XkEnvCheckService.save/check} 的 xk:env:* 权限 —— 开工的是车间操作工,
     * 不是质量管理员; 权限门禁由开工接口自身的 production:* perm 承担.
     */
    private List<Long> writeEnvChecks(PrdOrder order, String stage, List<String> photos) {
        String workshop = workshopOf(stage);
        String action = STAGE_BAG.equals(stage) ? "制袋" : "吹膜";
        LocalDateTime now = LocalDateTime.now();
        String inspector = SecurityContext.getUsername();
        List<Long> ids = new ArrayList<>();
        for (String[] item : ENV_CHECKS) {
            XkEnvCheck env = new XkEnvCheck();
            env.setCheckNo(billNoGenerator.generate("EC"));
            env.setWorkshop(workshop);
            env.setEnvType(item[0]);
            env.setCheckTitle(workshop + " " + action + "开工前检查 · " + item[1]);
            env.setCheckContent("生产单 " + order.getBillNo() + " (" + StrUtil.nullToEmpty(order.getProductName())
                    + ") " + action + "开工, 现场确认" + item[1] + "符合食品接触材料生产要求: 合格。"
                    + "检查人=" + inspector + ", 记录由开工确认自动生成。");
            env.setPlannedAt(now);
            env.setInspector(inspector);
            env.setLevel(null);
            env.setRectifyStatus("NONE");
            env.setRectifyDesc("开工前生产环境确认全部合格, 无需整改");
            // MySQL JSON 列不接受空串, 必须给合法 JSON 文档
            env.setBeforePhotos(photos == null || photos.isEmpty() ? "[]" : toJsonArray(photos));
            env.setAfterPhotos("[]");
            env.setStatus(Constants.STATUS_CHECKED);
            env.setVersion(0);
            env.setRemark("AUTO-BY-WORK-START " + order.getBillNo());
            envMapper.insert(env);
            ids.add(env.getId());
            auditService.writeAudit("XK_ENV_CHECK", env.getCheckNo(), "CHECK",
                    "车间=" + env.getWorkshop() + " 类型=" + env.getEnvType()
                            + " 等级=无 负责人=- (开工确认自动生成)", null, null, null);
        }
        return ids;
    }

    /**
     * 开工投料登记: upsert {@code mes_batch}(RAW) + 写 {@code mes_batch_link}
     * (parent=原料批, billType=ISSUE, 带 prdOrderId/prdProcessId).
     *
     * <p>child_batch_no 留空 —— 本工序的膜卷批号要到半成品入库才产生, 与一期「领料时登记」一致;
     * 但 prdOrderId + billType=ISSUE + parent 非空这一组合正是
     * {@code MesReportService.resolveRawBatchForOrder} 的取数条件, 于是操作工手录的批号
     * 能直接打通 半成品卷 ← 原料批 的上游链路.
     */
    private void bindRawBatch(String batchNo, PrdOrder order, Long processId, MesWorkStartConfirm rec) {
        try {
            mesReportService.registerRawOnIssue(batchNo, null, null,
                    order.getId(), rec.getId(), rec.getConfirmNo());
            MesBatchLink link = new MesBatchLink();
            link.setParentBatchNo(batchNo);
            link.setPrdOrderId(order.getId());
            link.setPrdProcessId(processId);
            link.setBillType("ISSUE");
            link.setBillId(rec.getId());
            link.setBillNo(rec.getConfirmNo());
            linkMapper.insert(link);
        } catch (Exception e) {
            // 批号绑定失败不否决开工 (现场已拍照留证, 台账里仍有原始批号); 记 warn 供事后补录
            log.warn("[XK] 开工投料批号绑定失败 (不阻塞开工) order={} batchNo={} err={}",
                    order.getBillNo(), batchNo, e.getMessage());
        }
    }

    /**
     * 按该生产单「已审核半成品入库单」生成制袋领料单 (DRAFT), 扣除已被历史领料单领用过的批次.
     *
     * <p>v1.1.134 仓库口径修正: 领料单开在**半成品实际入库的那个仓** (入库单自己的 warehouse_id),
     * 不再取生产单的仓库 —— 生产单仓库是原料仓/成品仓时, 仓管审核出库会扣错仓或直接批次不足.
     * 半成品分散在多个仓时按仓拆成多张领料单 (一张单只能挂一个仓库).
     *
     * @return 领料单列表 (无可用半成品时返回空列表)
     */
    private List<PrdRequisition> buildSemiRequisitions(PrdOrder order, MesWorkStartConfirm rec) {
        List<PrdSemiIn> ins = semiInMapper.selectList(new LambdaQueryWrapper<PrdSemiIn>()
                .eq(PrdSemiIn::getPrdOrderId, order.getId())
                .eq(PrdSemiIn::getBillStatus, Constants.STATUS_CHECKED)
                .orderByAsc(PrdSemiIn::getId));
        if (ins.isEmpty()) {
            log.info("[XK] 制袋开工: 生产单 {} 暂无已审核半成品入库, 不生成领料单", order.getBillNo());
            return Collections.emptyList();
        }
        Set<String> issued = issuedBatchNos(order.getId());
        List<PrdSemiIn> pending = ins.stream()
                .filter(i -> StrUtil.isNotBlank(i.getBatchNo()) && !issued.contains(i.getBatchNo()))
                .collect(Collectors.toList());
        if (pending.isEmpty()) {
            log.info("[XK] 制袋开工: 生产单 {} 的半成品已被历史领料单领完, 不重复生成", order.getBillNo());
            return Collections.emptyList();
        }
        // 按入库仓分组 (LinkedHashMap 保持入库单 id 顺序, 首单稳定)
        Map<Long, List<PrdSemiIn>> byWarehouse = new LinkedHashMap<>();
        for (PrdSemiIn in : pending) {
            Long wh = in.getWarehouseId() != null ? in.getWarehouseId() : order.getWarehouseId();
            byWarehouse.computeIfAbsent(wh, k -> new ArrayList<>()).add(in);
        }
        List<PrdRequisition> result = new ArrayList<>();
        for (Map.Entry<Long, List<PrdSemiIn>> e : byWarehouse.entrySet()) {
            PrdRequisition req = new PrdRequisition();
            req.setBillNo(billNoGenerator.generate(Constants.BILL_RQ));
            req.setBillDate(LocalDate.now());
            req.setPrdOrderId(order.getId());
            req.setPrdOrderNo(order.getBillNo());
            req.setWarehouseId(e.getKey());
            req.setWorkshop(workshopOf(STAGE_BAG));
            req.setBillType("ISSUE");
            req.setBillStatus(Constants.STATUS_DRAFT);
            req.setRemark("制袋开工自动领用半成品 (开工确认 " + rec.getConfirmNo() + "), 待仓管审核出库");
            reqMapper.insert(req);
            int line = 0;
            for (PrdSemiIn in : e.getValue()) {
                PrdRequisitionDetail d = new PrdRequisitionDetail();
                d.setRequisitionId(req.getId());
                d.setLineNo(++line);
                d.setMaterialType("SEMI");
                d.setProductId(in.getProductId());
                d.setProductCode(in.getProductCode());
                d.setProductName(in.getProductName());
                d.setUnitId(in.getUnitId());
                d.setUnitName(in.getUnitName());
                d.setQty(in.getQty());
                d.setBatchNo(in.getBatchNo());
                d.setPrice(in.getPrice());
                d.setAmount(in.getAmount());
                reqDetailMapper.insert(d);
            }
            log.info("[XK] 制袋开工自动领料: requisitionNo={} warehouseId={} lines={}",
                    req.getBillNo(), e.getKey(), e.getValue().size());
            result.add(req);
        }
        return result;
    }

    /** 该生产单历史领料/补料明细里出现过的批次号 (去重用). */
    private Set<String> issuedBatchNos(Long orderId) {
        List<PrdRequisition> reqs = reqMapper.selectList(new LambdaQueryWrapper<PrdRequisition>()
                .eq(PrdRequisition::getPrdOrderId, orderId)
                .in(PrdRequisition::getBillType, "ISSUE", "REPLENISH"));
        if (reqs.isEmpty()) return new LinkedHashSet<>();
        Set<String> result = new LinkedHashSet<>();
        for (PrdRequisition r : reqs) {
            List<PrdRequisitionDetail> ds = reqDetailMapper.selectByRequisitionId(r.getId());
            for (PrdRequisitionDetail d : ds) {
                if (StrUtil.isNotBlank(d.getBatchNo())) result.add(d.getBatchNo().trim());
            }
        }
        return result;
    }

    private Map<String, Object> result(MesWorkStartConfirm rec, List<String> unknownBatches) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirm", rec);
        m.put("confirmNo", rec.getConfirmNo());
        m.put("rawBatchNos", StrUtil.split(StrUtil.nullToEmpty(rec.getRawBatchNos()), ','));
        m.put("unknownBatchNos", unknownBatches);
        m.put("requisitionNo", rec.getRequisitionNo());
        return m;
    }

    private List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        Set<String> set = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty()) set.add(v);
        }
        return new ArrayList<>(set);
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
