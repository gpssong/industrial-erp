package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.production.entity.PrdSemiIn;
import com.industrial.erp.modules.production.mapper.PrdSemiInMapper;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * v1.1.98 半成品入库 — 打通 原料批 → 半成品卷批 追溯链.
 * 仿 PrdFinishedInService (inStock + MES 批次登记 + QMS 触发器), 区别:
 *   - billNo 前缀 PSI, 台账 LEDGER_PROD_IN (半成品也走生产入库台账)
 *   - MES 登记 SEMI 批次 (registerSemiOnFinish), QMS 触发 FAI 草稿 (createOnSemiIn)
 *   - **硬门禁**: 审核必须关联合格 FAI (hasPassedFai), 仿采购入库 XK 门禁 (hasPassedIqc)
 */
@Service
public class PrdSemiInService {

    private static final Logger log = LoggerFactory.getLogger(PrdSemiInService.class);

    public PrdSemiInService(PrdSemiInMapper mapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator,
                            StockService stockService, PermissionService permService,
                            com.industrial.erp.modules.mes.service.MesReportService mesReportService,
                            QmsInspectionService qmsInspectionService,
                            com.industrial.erp.modules.production.mapper.PrdOrderMapper prdOrderMapper,
                            ProductDeriveService deriveService) {
        this.mapper = mapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
        this.mesReportService = mesReportService;
        this.qmsInspectionService = qmsInspectionService;
        this.prdOrderMapper = prdOrderMapper;
        this.deriveService = deriveService;
    }

    private final PrdSemiInMapper mapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;
    private final com.industrial.erp.modules.mes.service.MesReportService mesReportService;
    private final QmsInspectionService qmsInspectionService;
    /** v1.1.142: 规格/单位 缺失时回查工单快照 + 商品主数据兜底 */
    private final com.industrial.erp.modules.production.mapper.PrdOrderMapper prdOrderMapper;
    private final ProductDeriveService deriveService;

    public IPage<PrdSemiIn> page(Integer pageNum, Integer pageSize, String billNo) {
        permService.requirePerm("production:semi-in:list");
        Page<PrdSemiIn> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrdSemiIn> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(PrdSemiIn::getBillNo, billNo);
        w.orderByDesc(PrdSemiIn::getId);
        return mapper.selectPage(p, w);
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "半成品入库", businessType = "ADD", saveParam = true)
    public void add(PrdSemiIn in) {
        permService.requirePerm("production:semi-in:add");
        if (in.getBillDate() == null) in.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(in.getBillNo())) in.setBillNo(billNoGenerator.generate(Constants.BILL_PSI));
        if (StrUtil.isBlank(in.getBillStatus())) in.setBillStatus(Constants.STATUS_DRAFT);
        if (in.getQty() == null) in.setQty(java.math.BigDecimal.ZERO);
        if (in.getPrice() == null) in.setPrice(java.math.BigDecimal.ZERO);
        in.setAmount(in.getPrice().multiply(in.getQty()));
        // v1.1.142: 半成品入库同样兜底 商品/规格/单位 (App semi-in 页直接把工单快照传过来, 工单上常年是空)
        com.industrial.erp.modules.production.entity.PrdOrder derivedOrder = in.getPrdOrderId() == null ? null
                : prdOrderMapper.selectById(in.getPrdOrderId());
        ProductDeriveService.InboundFields f = deriveService.inboundFields(
                in.getProductId(), in.getProductCode(), in.getProductName(),
                in.getSpec(), in.getUnitId(), in.getUnitName(), derivedOrder);
        in.setProductId(f.productId);
        in.setProductCode(f.productCode);
        in.setProductName(f.productName);
        in.setSpec(f.spec);
        in.setUnitId(f.unitId);
        in.setUnitName(f.unitName);
        mapper.insert(in);
    }

    /**
     * 审核半成品入库: 硬门禁 (合格 FAI 才放行) → 库存 inStock → 登记 SEMI 批次 → 生成 OQC 触发器.
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "半成品入库", businessType = "CHECK", saveParam = true)
    public void check(Long id) {
        permService.requirePerm("production:semi-in:check");
        PrdSemiIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_DRAFT.equals(in.getBillStatus())) throw BizException.of("状态不正确");

        // v1.1.102: 食品级商品 (is_food_grade=1) 保持 FAI 硬门禁; 工业品直接放行.
        if (qmsInspectionService.isFoodGradeProduct(in.getProductId())) {
            // XK 硬门禁: 必须关联合格 FAI 检验单 (PASS/PASS_DISPOSITION + CHECKED) 才放行.
            // 若该入库单尚未自动建 FAI 草稿, 先兜底建一张 (老数据/手工建的单), 但门禁仍按合格判定拒绝.
            if (!qmsInspectionService.hasPassedFai(id)) {
                // v1.1.138: 首检可能挂在「工单吹膜工序」上 (App 现场先首检后建入库单), 用关联口径找,
                // 报错时也要指出到底是哪张 FAI 没放行.
                com.industrial.erp.modules.qms.entity.MesQualityInspection fai = qmsInspectionService.findLinkedFaiForSemiIn(id);
                if (fai == null) {
                    throw BizException.of("XK 合规: 半成品入库单未关联 FAI 检验, 不能入库; 请先完成「半成品检验」并判定合格 (PASS)");
                }
                throw BizException.of("XK 合规: 关联 FAI 检验单 " + fai.getBillNo() + " 判定为 " + fai.getResult()
                        + (Constants.RESULT_PASS.equals(fai.getResult()) && !Constants.STATUS_CHECKED.equals(fai.getStatus()) ? " (尚未审核放行)" : "")
                        + ", 不合格/未放行不能入库; 请拒收或放行后重试");
            }
            // 冗余快照: 把合格 FAI 判定结果/报告号/抽样数/检验人写回入库单 (供 XK 前端展示 + 审计留痕)
            qmsInspectionService.snapshotFaiGate(in);
        } else {
            // 工业品 (is_food_grade != 1): 跳过 XK FAI 门禁直接放行; 若有合格 FAI 仍留痕 (null-safe, 无 FAI 时 no-op)
            qmsInspectionService.snapshotFaiGate(in);
        }

        BaseWarehouse wh = warehouseMapper.selectById(in.getWarehouseId());
        stockService.inStock(Constants.LEDGER_PROD_IN, in.getId(), in.getBillNo(), in.getId(),
                in.getWarehouseId(), wh == null ? "" : wh.getWarehouseName(), in.getLocationId(), null,
                in.getProductId(), in.getUnitId(), in.getUnitName(), in.getBatchNo(),
                in.getQty(), in.getPrice(), in.getBillNo(),
                null, null, "半成品入库 " + in.getBillNo());

        // v1.1.98 MES: 半成品入库 = SEMI 批次登记时机.
        // v1.1.99 C2: 原料批 (parent) 从同工单领料登记的 RAW 批自动取值 (打通 半成品→原料 上游断链), 无则留空.
        if (StrUtil.isNotBlank(in.getBatchNo())) {
            try {
                String rawBatchNo = mesReportService.resolveRawBatchForOrder(in.getPrdOrderId());
                mesReportService.registerSemiOnFinish(in.getBatchNo(), in.getPrdOrderId(), in.getPrdOrderNo(),
                        in.getBillNo(), rawBatchNo, in.getQty());
            } catch (Exception e) {
                log.warn("[MES] 半成品入库登记 SEMI 批次失败 (不阻塞库存审核) batchNo={} err={}", in.getBatchNo(), e.getMessage());
            }
        }

        LambdaUpdateWrapper<PrdSemiIn> w = new LambdaUpdateWrapper<>();
        w.eq(PrdSemiIn::getId, id).set(PrdSemiIn::getBillStatus, Constants.STATUS_CHECKED);
        // 写回 XK 快照列 (MyBatis-Plus updateById 会按非 null 字段 SET, 这里用 wrapper 全量覆盖 4 列)
        w.set(PrdSemiIn::getInspectionReportNo, in.getInspectionReportNo());
        w.set(PrdSemiIn::getInspectionResult, in.getInspectionResult());
        w.set(PrdSemiIn::getSampleQty, in.getSampleQty());
        w.set(PrdSemiIn::getInspectorName, in.getInspectorName());
        mapper.update(null, w);
        log.info("半成品入库审核: billNo={}", in.getBillNo());
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "半成品入库", businessType = "DELETE", saveParam = true)
    public void delete(Long id) {
        permService.requirePerm("production:semi-in:delete");
        PrdSemiIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在或已删除");
        if (!Constants.STATUS_DRAFT.equals(in.getBillStatus())) {
            throw BizException.of("只有草稿状态可删除, 已审核请先反审核");
        }
        LambdaUpdateWrapper<PrdSemiIn> w = new LambdaUpdateWrapper<>();
        w.eq(PrdSemiIn::getId, id).set(PrdSemiIn::getDeleted, 1);
        mapper.update(null, w);
    }

    /** 反审核 (CHECKED→DRAFT). 同步出库冲掉 check() 时入的库存. MES/QMS 不回退 (走人工修正). */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "半成品入库", businessType = "UNCHECK", saveParam = true)
    public void uncheck(Long id) {
        permService.requirePerm("production:semi-in:uncheck");
        PrdSemiIn in = mapper.selectById(id);
        if (in == null) throw BizException.of("入库单不存在");
        if (!Constants.STATUS_CHECKED.equals(in.getBillStatus())) {
            throw BizException.of("只有已审核状态可反审核");
        }
        BaseWarehouse wh = in.getWarehouseId() == null ? null : warehouseMapper.selectById(in.getWarehouseId());
        stockService.outStock(Constants.LEDGER_PROD_IN, in.getId(), in.getBillNo(), in.getId(),
                in.getWarehouseId(), wh == null ? "" : wh.getWarehouseName(), in.getLocationId(), null,
                in.getProductId(), in.getUnitId(), in.getUnitName(), in.getBatchNo(),
                in.getQty(), in.getPrice(), in.getBillNo(),
                null, null, "反审核半成品入库 " + in.getBillNo());
        LambdaUpdateWrapper<PrdSemiIn> w = new LambdaUpdateWrapper<>();
        w.eq(PrdSemiIn::getId, id).set(PrdSemiIn::getBillStatus, Constants.STATUS_DRAFT);
        mapper.update(null, w);
        log.info("半成品入库反审核: billNo={}", in.getBillNo());
    }
}
