package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class PrdFinishedInService {

    private static final Logger log = LoggerFactory.getLogger(PrdFinishedInService.class);

    public PrdFinishedInService(PrdFinishedInMapper mapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService, com.industrial.erp.modules.mes.service.MesReportService mesReportService, QmsInspectionService qmsInspectionService) {
        this.mapper = mapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
        this.mesReportService = mesReportService;
        this.qmsInspectionService = qmsInspectionService;
    }
    private final PrdFinishedInMapper mapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;
    /** v1.1.80 MES: 成品入库登记 FG 成品批次 + 写批次流向 (child=成品批, parent=半成品卷批 可空). */
    private final com.industrial.erp.modules.mes.service.MesReportService mesReportService;
    /** v1.1.90+: QMS 自动触发器 (OQC 草稿在 check() 末 try/catch 调用) */
    private final QmsInspectionService qmsInspectionService;

    public IPage<PrdFinishedIn> page(Integer pageNum, Integer pageSize, String billNo) {
        permService.requirePerm("production:finished-in:list");
        Page<PrdFinishedIn> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrdFinishedIn> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(PrdFinishedIn::getBillNo, billNo);
        w.orderByDesc(PrdFinishedIn::getId);
        return mapper.selectPage(p, w);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="成品入库", businessType="ADD", saveParam=true)
    public void add(PrdFinishedIn in) {
        permService.requirePerm("production:finished-in:add");
        if (in.getBillDate() == null) in.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(in.getBillNo())) in.setBillNo(billNoGenerator.generate(Constants.BILL_PFI));
        if (StrUtil.isBlank(in.getBillStatus())) in.setBillStatus(Constants.STATUS_DRAFT);
        if (in.getQty() == null) in.setQty(java.math.BigDecimal.ZERO);
        if (in.getPrice() == null) in.setPrice(java.math.BigDecimal.ZERO);
        in.setAmount(in.getPrice().multiply(in.getQty()));
        mapper.insert(in);
        // v1.1.102: 仅食品级商品 (is_food_grade=1) 建单时自动建 OQC 草稿 (先检后入);
        // 工业品不自动建 OQC, 可直接入库 (可现场签, 不强制先检后入).
        // 从 check() 挪到 add(), 避免「审核后才建草稿→草稿未 PASS→永远审不了」死锁.
        if (qmsInspectionService.isFoodGradeProduct(in.getProductId())) {
            try {
                // v1.1.135: App「生产单详情 → 做成品检」先检后入时, OQC 草稿挂在工单名下;
                // 这里把它归并到本张入库单, 避免一张工单出现两条出厂检验记录.
                if (!qmsInspectionService.relinkOqcToFinishedIn(in)) {
                    qmsInspectionService.createOnFinish(in);
                }
            } catch (Exception ex) {
                log.warn("[PrdFinishedIn.add] QMS OQC auto-create failed (non-blocking): {}", ex.getMessage());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("production:finished-in:check");
        PrdFinishedIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(in.getBillStatus())) throw BizException.of("状态不正确");
        // v1.1.102: 食品级商品 (is_food_grade=1) 保留 工序完工硬门禁 + OQC 软门禁;
        // 工业品 (is_food_grade != 1) 跳过全部 XK 门禁直接入库.
        if (qmsInspectionService.isFoodGradeProduct(in.getProductId())) {
            // v1.1.99 C1 工单状态机硬门禁: 关联了生产工单 (prdOrderId 非空) 的成品入库,
            // 审核前要求该工单全部工序已完工 (工序驱动主流程闭环). 无工单的独立入库单不拦.
            // 没配工序链的存量工单 allProcessesFinished=true, 不回归.
            if (in.getPrdOrderId() != null && !mesReportService.allProcessesFinished(in.getPrdOrderId())) {
                throw BizException.of("生产工单存在未完工工序, 不能成品入库: "
                        + String.join("、", mesReportService.listUnfinishedProcessNames(in.getPrdOrderId())));
            }
            // v1.1.99 C3 质量门禁对称化 (软门禁, 零回归): 若该入库单已关联 OQC 检验单, 必须已合格放行 (PASS/PASS_DISPOSITION + CHECKED) 才允许入库;
            // 无 OQC 单 (旧数据/未建) 不拦, 保持向后兼容. 与半成品 FAI 硬门禁对称, 但成品侧避免历史数据死锁故用软门禁.
            // v1.1.135: App 端按生产单先做的成品检验 (表头挂 PRD_ORDER) 也算放行依据, 归并失败时按工单兜底找.
            com.industrial.erp.modules.qms.entity.MesQualityInspection oqc = qmsInspectionService.findOqcByFinishedInId(id);
            if (oqc == null) oqc = qmsInspectionService.findLinkedOqcByOrder(in.getPrdOrderId());
            if (oqc != null && !qmsInspectionService.isPassedOqc(oqc)) {
                throw BizException.of("XK 合规: 成品入库单关联 OQC 检验单 " + oqc.getBillNo()
                        + " 判定为 " + oqc.getResult()
                        + (Constants.RESULT_PASS.equals(oqc.getResult()) && !Constants.STATUS_CHECKED.equals(oqc.getStatus()) ? " (尚未审核放行)" : "")
                        + ", 未合格不能入库; 请放行 OQC 或拒收后重试");
            }
        }
        BaseWarehouse wh = warehouseMapper.selectById(in.getWarehouseId());
        stockService.inStock(Constants.LEDGER_PROD_IN, in.getId(), in.getBillNo(), in.getId(),
                in.getWarehouseId(), wh == null ? "" : wh.getWarehouseName(), in.getLocationId(), null,
                in.getProductId(), in.getUnitId(), in.getUnitName(), in.getBatchNo(),
                in.getQty(), in.getPrice(), in.getBillNo(),
                null, null, "成品入库 " + in.getBillNo());
        // v1.1.80 MES: 成品入库 = 成品批次登记时机. 半成品卷批 (parent) 一期留空 (膜卷二期),
        // 客户流向 (customer_id) 在销售出库消耗成品时二期补. 一期先登记 FG 主表 + 无 parent 的流向.
        if (cn.hutool.core.util.StrUtil.isNotBlank(in.getBatchNo())) {
            try {
                mesReportService.registerFgOnFinish(in.getBatchNo(), in.getPrdOrderId(),
                        in.getPrdOrderNo(), in.getBillNo(), null, null, in.getQty());
            } catch (Exception e) {
                log.warn("[MES] 成品入库登记 FG 批次失败 (不阻塞库存审核) batchNo={} err={}", in.getBatchNo(), e.getMessage());
            }
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PrdFinishedIn> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(PrdFinishedIn::getId, id).set(PrdFinishedIn::getBillStatus, Constants.STATUS_CHECKED);
        mapper.update(null, w);
    }

    /**
     * 删除入库单 (仅 DRAFT 可删, 软删). 已审核的先反审核.
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "成品入库", businessType = "DELETE", saveParam = true)
    public void delete(Long id) {
        permService.requirePerm("production:finished-in:delete");
        PrdFinishedIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(in.getBillStatus())) {
            throw BizException.of("只有草稿状态可删除, 已审核请先反审核");
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PrdFinishedIn> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(PrdFinishedIn::getId, id).set(PrdFinishedIn::getDeleted, 1);
        mapper.update(null, w);
    }

    /**
     * 反审核 (CHECKED→DRAFT). 同步出库冲掉 check() 时入的库存.
     * 注意: MES FG 批次 / OQC 草稿不回退 (与采购入库一致, 走人工修正), 仅回退库存账.
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "成品入库", businessType = "UNCHECK", saveParam = true)
    public void uncheck(Long id) {
        permService.requirePerm("production:finished-in:uncheck");
        PrdFinishedIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_CHECKED.equals(in.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }
        BaseWarehouse wh = in.getWarehouseId() == null ? null : warehouseMapper.selectById(in.getWarehouseId());
        // check() 时入的库存, 反审核出库冲掉 (成品入库是入, 反审核要出).
        stockService.outStock(Constants.LEDGER_PROD_IN, in.getId(), in.getBillNo(), in.getId(),
                in.getWarehouseId(), wh == null ? "" : wh.getWarehouseName(), in.getLocationId(), null,
                in.getProductId(), in.getUnitId(), in.getUnitName(), in.getBatchNo(),
                in.getQty(), in.getPrice(), in.getBillNo(),
                null, null, "反审核成品入库 " + in.getBillNo());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PrdFinishedIn> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(PrdFinishedIn::getId, id).set(PrdFinishedIn::getBillStatus, Constants.STATUS_DRAFT);
        mapper.update(null, w);
        log.info("成品入库反审核: billNo={}", in.getBillNo());
    }
}
