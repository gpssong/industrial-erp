package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.utils.BillNoGenerator;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产加工单服务
 * 核心: BOM 展开 -> 自动生成领料单(主料+辅料*损耗) -> 实际成本归集
 */
@Service
public class PrdOrderService {

    private final PrdOrderMapper orderMapper;
    private final PrdRequisitionMapper reqMapper;
    private final PrdRequisitionDetailMapper reqDetailMapper;
    private final PrdFinishedInMapper finishedInMapper;
    private final PrdBomService bomService;
    private final BaseProductMapper productMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BillNoGenerator billNoGenerator;
    private final StockService stockService;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;
    /** v1.1.80 MES: 开单时按产品默认工序链展开 prd_process 工序实例. */
    private final com.industrial.erp.modules.mes.service.MesReportService mesReportService;

    public PrdOrderService(PrdOrderMapper orderMapper, PrdRequisitionMapper reqMapper, PrdRequisitionDetailMapper reqDetailMapper, PrdFinishedInMapper finishedInMapper, PrdBomService bomService, BaseProductMapper productMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService, OperLogPublisher operLogPublisher, com.industrial.erp.modules.mes.service.MesReportService mesReportService) {
        this.orderMapper = orderMapper;
        this.reqMapper = reqMapper;
        this.reqDetailMapper = reqDetailMapper;
        this.finishedInMapper = finishedInMapper;
        this.bomService = bomService;
        this.productMapper = productMapper;
        this.warehouseMapper = warehouseMapper;
        this.billNoGenerator = billNoGenerator;
        this.stockService = stockService;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
        this.mesReportService = mesReportService;
    }

    public IPage<PrdOrder> page(Integer pageNum, Integer pageSize, String billNo, String billStatus, String productName) {
        permService.requirePerm("production:order:list");
        Page<PrdOrder> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrdOrder> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(billNo)) w.like(PrdOrder::getBillNo, billNo);
        if (StrUtil.isNotBlank(billStatus)) w.eq(PrdOrder::getBillStatus, billStatus);
        // 按产品名称查询 (生产单本身就是针对单个产品)
        if (StrUtil.isNotBlank(productName)) {
            w.and(wq -> wq.like(PrdOrder::getProductName, productName)
                    .or().like(PrdOrder::getProductCode, productName));
        }
        w.orderByDesc(PrdOrder::getId);
        IPage<PrdOrder> res = orderMapper.selectPage(p, w);
        // 批量注入商品规格属性 (N+1 优化: 之前每行 selectById, 现在 selectBatchIds 一次)
        Set<Long> productIds = new HashSet<>();
        for (PrdOrder order : res.getRecords()) {
            if (order.getProductId() != null) productIds.add(order.getProductId());
        }
        Map<Long, BaseProduct> prodById = productIds.isEmpty() ? java.util.Collections.emptyMap()
                : productMapper.selectBatchIds(productIds).stream()
                        .filter(prod2 -> prod2 != null)
                        .collect(java.util.stream.Collectors.toMap(BaseProduct::getId, prod2 -> prod2, (a, b) -> a));
        for (PrdOrder order : res.getRecords()) {
            BaseProduct prod = order.getProductId() == null ? null : prodById.get(order.getProductId());
            if (prod != null) {
                order.setPThickness(prod.getThickness());
                order.setPWidth(prod.getWidth());
                order.setPDensity(prod.getDensity());
                order.setPGramWeight(prod.getGramWeight());
                order.setPMaterial(prod.getMaterial());
                order.setPColorNo(prod.getColorNo());
            }
        }
        return res;
    }

    public PrdOrder detail(Long id) {
        PrdOrder order = orderMapper.selectById(id);
        // JOIN 注入商品规格属性 + BOM 备注 (打印模板用)
        if (order != null && order.getProductId() != null) {
            BaseProduct p = productMapper.selectById(order.getProductId());
            if (p != null) {
                order.setPThickness(p.getThickness());
                order.setPWidth(p.getWidth());
                order.setPDensity(p.getDensity());
                order.setPGramWeight(p.getGramWeight());
                order.setPMaterial(p.getMaterial());
                order.setPColorNo(p.getColorNo());
                order.setModel(p.getModel());
                // v1.1.100 食品合规 5 字段从 base_product 带出 (食品级成品回显 执行标准/备案/用途/限值)
                order.setPFoodExecStd(p.getFoodExecStd());
                order.setPFoodFilingNo(p.getFoodFilingNo());
                order.setPFoodUseDesc(p.getFoodUseDesc());
                order.setPFoodMigrationLimit(p.getFoodMigrationLimit());
                order.setPIsFoodGrade(p.getIsFoodGrade());
            }
        }
        // 从 BOM 表取 bomCode/bomName + 备注
        if (order != null && order.getBomId() != null) {
            PrdBom bom = bomService.detail(order.getBomId());
            if (bom != null) {
                order.setBomCode(bom.getBomCode());
                order.setBomName(bom.getBomName());
                if (bom.getRemark() != null) {
                    order.setBomRemark(bom.getRemark());
                }
            }
        }
        // 加载领料明细 (生产加工单打印用: 跨所有领料单汇总明细行)
        if (order != null) {
            order.setRequisitionDetails(reqDetailMapper.selectByPrdOrderId(id));
        }
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="生产加工单", businessType="ADD", saveParam=true)
    public Long add(PrdOrder order) {
        permService.requirePerm("production:order:add");
        if (order.getBillDate() == null) order.setBillDate(LocalDate.now());
        if (StrUtil.isBlank(order.getBillNo())) {
            order.setBillNo(billNoGenerator.generate(Constants.BILL_PD));
        }
        if (StrUtil.isBlank(order.getBillStatus())) order.setBillStatus(Constants.STATUS_DRAFT);
        if (order.getPlanQty() == null) order.setPlanQty(BigDecimal.ZERO);
        if (order.getLossRate() == null) order.setLossRate(BigDecimal.ZERO);
        BaseProduct p = productMapper.selectById(order.getProductId());
        if (p != null) {
            order.setProductCode(p.getProductCode());
            order.setProductName(p.getProductName());
            order.setSpec(p.getSpec());
            order.setModel(p.getModel());
            // 配方迁移: 若前端没传 bomId 但成品选了配方, 自动从成品上同步过来
            if (order.getBomId() == null && p.getBomId() != null) {
                order.setBomId(p.getBomId());
            }
        }
        // 从 BOM 表取 bomNo 填入生产单(打印时需要显示)
        if (order.getBomId() != null && StrUtil.isBlank(order.getBomNo())) {
            PrdBom bom = bomService.detail(order.getBomId());
            if (bom != null) {
                order.setBomNo(bom.getBomCode());
                // 如果前端没传 productName, 从 BOM 关联产品取
                if (p == null && bom.getProductId() != null) {
                    BaseProduct bp = productMapper.selectById(bom.getProductId());
                    if (bp != null) {
                        order.setProductId(bp.getId());
                        order.setProductCode(bp.getProductCode());
                        order.setProductName(bp.getProductName());
                        order.setSpec(bp.getSpec());
                    }
                }
            }
        }
        orderMapper.insert(order);
        return order.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="生产加工单", businessType="EDIT", saveParam=true)
    public void update(PrdOrder order) {
        permService.requirePerm("production:order:edit");
        PrdOrder exist = orderMapper.selectById(order.getId());
        if (exist == null) throw BizException.of("生产单不存在");
        if (!Constants.STATUS_DRAFT.equals(exist.getBillStatus())) {
            throw BizException.of("只能编辑草稿状态的生产单");
        }
        if (order.getProductId() != null && exist.getProductId() != order.getProductId()) {
            BaseProduct p = productMapper.selectById(order.getProductId());
            if (p != null) {
                order.setProductCode(p.getProductCode());
                order.setProductName(p.getProductName());
                order.setSpec(p.getSpec());
                order.setModel(p.getModel());
            }
        }
        if (order.getBomId() != null) {
            PrdBom bom = bomService.detail(order.getBomId());
            if (bom != null) {
                order.setBomNo(bom.getBomCode());
            }
        }
        orderMapper.updateById(order);
    }

    /**
     * 开工: 按 BOM 自动展开领料单. v1.1.103+ 容错: 未配置 BOM 的工单 (工业品/临时生产常见)
     * 不再硬阻断, 而是降级为"无 BOM 开工" (不展开领料单, 不展开工序, 状态置 RELEASED,
     * 提示用户后续手动到领料管理创建领料单, 保持零阻塞).
     */
    @Transactional(rollbackFor = Exception.class)
    public Long release(Long orderId) {
        permService.requirePerm("production:order:release");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在");
        if (!Constants.STATUS_DRAFT.equals(order.getBillStatus()) && !Constants.STATUS_RELEASED.equals(order.getBillStatus())) {
            throw BizException.of("当前状态不可开工");
        }
        // v1.1.103+: BOM 缺失降级 (不抛错) — 工业品吹膜厂实际场景工单常先开工后补 BOM.
        // 走完下面 "无 BOM 降级" 路径后, 工序展开也跳过, 状态直接置 RELEASED.
        // v1.1.103+ 降级标志: 领料展开需要 BOM 明细 + 可用仓库 (生产单的或默认仓库)。
        // 缺仓库时开工本身不该被卡死 (仓库是领料/入库环节才需要的) → 跳过领料展开, 状态照样 RELEASED,
        // 用户后续手动到「领料管理」建领料单时再填仓库。缺 BOM 则 BOM 相关展开都跳过。
        boolean noBom = (order.getBomId() == null);
        PrdBom bom = noBom ? null : bomService.detail(order.getBomId());
        boolean bomNoDetails = noBom || bom == null || bom.getDetails() == null || bom.getDetails().isEmpty();

        // 解析领料用仓库: 优先生产单自带的, 没有则仓库表 is_default=1 的默认仓库; 都没有 → null (降级).
        Long reqWarehouseId = order.getWarehouseId();
        if (reqWarehouseId == null) {
            BaseWarehouse defaultWh = warehouseMapper.selectOne(new LambdaQueryWrapper<BaseWarehouse>()
                    .eq(BaseWarehouse::getIsDefault, 1)
                    .eq(BaseWarehouse::getDeleted, 0)
                    .last("LIMIT 1"));
            reqWarehouseId = defaultWh == null ? null : defaultWh.getId();
        }
        boolean canExpand = !bomNoDetails && reqWarehouseId != null;

        if (!bomNoDetails) {
            // v1.1.119 — BOM 明细 base_qty 的真义是**重量配比份数**, 不是「生产 1 个成品用多少料」.
            //
            //   旧算法 (错的): ratio = planQty / bom.baseQty ; need = baseQty × ratio
            //     配比无量纲 (Σ=206.6 份), bom.baseQty 通常是 NULL 回落 1 → need 退化成
            //     baseQty × planQty. 实测 plan_qty=750 → 领 lldpe7042 131250 卷, 荒谬.
            //
            //   新算法 (用户拍板, 与车间口径一致):
            //     成品总重(kg) = planQty × gram_weight / 1000          (base_product.gram_weight, g/件)
            //     原料用量(kg) = 成品总重 × (本项配比 / Σ全部配比) × (1 + 本项损耗率/100)
            //     (原料主单位已在 sql/128 统一为 kg, 所以算出的 kg 直接就是领料量)
            BigDecimal sumRatio = BigDecimal.ZERO;
            for (PrdBomDetail bd : bom.getDetails()) {
                sumRatio = sumRatio.add(bd.getBaseQty() == null ? BigDecimal.ZERO : bd.getBaseQty());
            }

            // 成品克重 (g/件) — 算不出总重就没法按配比分摊, 降级不卡死开工
            BaseProduct prod = order.getProductId() == null ? null
                    : productMapper.selectById(order.getProductId());
            BigDecimal gram = prod == null ? null : prod.getGramWeight();

            if (gram == null || gram.compareTo(BigDecimal.ZERO) <= 0 || sumRatio.compareTo(BigDecimal.ZERO) <= 0) {
                // 降级: 打日志提醒, 不阻塞开工 (与上方 BOM 缺失/仓库缺失降级同一风格)
                org.slf4j.LoggerFactory.getLogger(PrdOrderService.class)
                        .warn("[PrdOrder.release] 工单 {} 跳过自动领料展开 — 成品克重缺失(gramWeight={}, 产品={}) 或 BOM 配比合计为 0(Σ={}). "
                                      + "配比版用量 = 成品总重 × 本项/Σ配比, 缺任一项都算不出用量. 请到「基础数据 → 商品」补成品克重, 或到「生产管理 → BOM」核对配比份数. 状态仍置 RELEASED, 用户可后续手动建领料单.",
                                order.getBillNo(), gram, order.getProductName(), sumRatio);
            } else {
                // 成品总重: planQty(件) × 克重(g/件) ÷ 1000 → kg
                BigDecimal totalKg = order.getPlanQty()
                        .multiply(gram)
                        .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);

                if (canExpand) {
                    PrdRequisition req = new PrdRequisition();
                    req.setBillNo(billNoGenerator.generate(Constants.BILL_RQ));
                    req.setBillDate(LocalDate.now());
                    req.setPrdOrderId(order.getId());
                    req.setPrdOrderNo(order.getBillNo());
                    req.setWarehouseId(reqWarehouseId);
                    req.setWorkshop(order.getWorkshop());
                    req.setWorkshopId(order.getWorkshopId());
                    req.setBillType("ISSUE");
                    req.setBillStatus(Constants.STATUS_DRAFT);
                    req.setDetails(new java.util.ArrayList<>());
                    int line = 0;
                    for (PrdBomDetail bd : bom.getDetails()) {
                        PrdRequisitionDetail rd = new PrdRequisitionDetail();
                        rd.setLineNo(++line);
                        rd.setProductId(bd.getProductId());
                        rd.setProductCode(bd.getProductCode());
                        rd.setProductName(bd.getProductName());
                        rd.setUnitId(bd.getUnitId());
                        rd.setUnitName(bd.getUnitName());
                        BigDecimal base = bd.getBaseQty() == null ? BigDecimal.ZERO : bd.getBaseQty();
                        BigDecimal loss = bd.getLossRate() == null ? BigDecimal.ZERO : bd.getLossRate();
                        // 配比份额 = 本项 / Σ全部 ; 再乘成品总重和 (1+损耗率)
                        BigDecimal share = base.divide(sumRatio, 10, RoundingMode.HALF_UP);
                        BigDecimal need = totalKg
                                .multiply(share)
                                .multiply(BigDecimal.ONE.add(loss.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
                        rd.setQty(need.setScale(4, RoundingMode.HALF_UP));
                        req.getDetails().add(rd);
                    }
                    reqMapper.insert(req);
                    for (PrdRequisitionDetail rd : req.getDetails()) {
                        rd.setId(null);
                        rd.setRequisitionId(req.getId());
                        reqDetailMapper.insert(rd);
                    }
                } else {
                    // BOM 有明细但仓库缺失 (生产单没填 + 无默认仓库): 降级跳过领料展开, 不阻塞开工.
                    org.slf4j.LoggerFactory.getLogger(PrdOrderService.class)
                            .warn("[PrdOrder.release] 工单 {} BOM 有明细但未填仓库且仓库表无默认仓库, 跳过自动领料单展开 (仓库为领料环节所需, 不阻塞开工). 用户可后续手动到「领料管理」建领料单并填写仓库.",
                                    order.getBillNo());
                }
            }
        }

        // 更新生产单状态
        PrdOrder upd = new PrdOrder();
        upd.setId(orderId);
        upd.setBillStatus(Constants.STATUS_RELEASED);
        // v1.1.102 XK — 留痕: 记录是哪个账号(吹膜工)点的开工
        upd.setReleaseBy(SecurityContext.getUserId());
        upd.setReleaseName(SecurityContext.getUsername());
        orderMapper.updateById(upd);

        // v1.1.80 MES: 开单展开工序实例 (产品默认工序链 mes_process_def.is_default=1).
        // 没配工序定义的产品 → 不展开 (零破坏, 存量单照常开工只是没有工序可报工).
        // v1.1.103+: 无 BOM 工单也跳过工序展开 (工序跟着 BOM 才有意义).
        boolean processesExpanded = false;
        if (!bomNoDetails) {
            try {
                java.util.List<Long> procIds = mesReportService.expandProcessesOnRelease(order);
                processesExpanded = !procIds.isEmpty();
            } catch (Exception e) {
                // 工序展开失败不阻塞开单主流程 (领料单已生成), 记日志降级.
                org.slf4j.LoggerFactory.getLogger(PrdOrderService.class)
                        .warn("[MES] 开单展开工序失败 (不阻塞开工) orderId={} err={}", orderId, e.getMessage());
            }
        }
        // v1.1.99 C1: 展开了工序链 → 工单进入「生产中」(STATUS_PRODUCING), 让死枚举活起来.
        // 没展开工序的存量单维持 RELEASED, 行为不变.
        if (processesExpanded) {
            PrdOrder prod = new PrdOrder();
            prod.setId(orderId);
            prod.setBillStatus(Constants.STATUS_PRODUCING);
            orderMapper.updateById(prod);
        }

        // v1.1.103+ 返回工单 id (兼容前端既有调用: var r = await api.prdOrderRelease(id); 显示 r.data)
        return orderId;
    }

    /**
     * 成品入库
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(Long orderId, BigDecimal goodQty, BigDecimal lossQty, Long warehouseId) {
        permService.requirePerm("production:order:finish");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在");

        // v1.1.99 C1 工单状态机硬门禁: 配置了工序链的工单, 必须全部工序完工才允许完工.
        // 没配工序链 (无 prd_process 实例) 的存量单 allProcessesFinished=true, 不阻塞老流程.
        if (!mesReportService.allProcessesFinished(orderId)) {
            throw BizException.of("存在未完工工序, 不能完工: "
                    + String.join("、", mesReportService.listUnfinishedProcessNames(orderId)));
        }

        BigDecimal actual = (goodQty == null ? BigDecimal.ZERO : goodQty).add(lossQty == null ? BigDecimal.ZERO : lossQty);
        BigDecimal lossRate = order.getPlanQty().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : (lossQty == null ? BigDecimal.ZERO : lossQty)
                .multiply(new BigDecimal("100"))
                .divide(order.getPlanQty(), 4, RoundingMode.HALF_UP);

        PrdFinishedIn fi = new PrdFinishedIn();
        fi.setBillNo(billNoGenerator.generate(Constants.BILL_PFI));
        fi.setBillDate(LocalDate.now());
        fi.setPrdOrderId(order.getId());
        fi.setPrdOrderNo(order.getBillNo());
        fi.setProductId(order.getProductId());
        fi.setProductCode(order.getProductCode());
        fi.setProductName(order.getProductName());
        fi.setSpec(order.getSpec());
        fi.setUnitId(order.getUnitId());
        fi.setUnitName(order.getUnitName());
        fi.setQty(goodQty == null ? BigDecimal.ZERO : goodQty);
        fi.setWarehouseId(warehouseId == null ? order.getWarehouseId() : warehouseId);
        fi.setBatchNo("PD" + order.getBillNo());
        fi.setBillStatus(Constants.STATUS_DRAFT);
        finishedInMapper.insert(fi);

        // 入库
        String whName = null;
        if (fi.getWarehouseId() != null) {
            BaseWarehouse wh = warehouseMapper.selectById(fi.getWarehouseId());
            whName = wh != null ? wh.getWarehouseName() : "";
        }
        // 价格 = 归集的成本/良品数
        BigDecimal cost = order.getCostAmount() == null ? BigDecimal.ZERO : order.getCostAmount();
        BigDecimal price = goodQty == null || goodQty.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : cost.divide(goodQty, 4, RoundingMode.HALF_UP);
        fi.setPrice(price);
        fi.setAmount(cost);
        finishedInMapper.updateById(fi);

        stockService.inStock(
                Constants.LEDGER_PROD_IN, fi.getId(), fi.getBillNo(), fi.getId(),
                fi.getWarehouseId(), whName, null, null,
                fi.getProductId(), fi.getUnitId(), fi.getUnitName(), fi.getBatchNo(),
                fi.getQty(), price, order.getBillNo(),
                null, null, "成品入库 " + fi.getBillNo()
        );

        PrdOrder upd = new PrdOrder();
        upd.setId(orderId);
        upd.setBillStatus(Constants.STATUS_FINISHED);
        upd.setActualQty(actual);
        upd.setGoodQty(goodQty);
        upd.setLossQty(lossQty);
        upd.setLossRate(lossRate);
        orderMapper.updateById(upd);
    }

    /**
     * 作废生产单: billStatus → CLOSED (已作废).
     * XK 合规: 生产记录禁止物理删除, 作废只改状态, 留痕 (OperLog).
     * 允许从 DRAFT / RELEASED 作废; 已完工 (FINISHED) 或已作废 (CLOSED) 不可再作废.
     */
    @Transactional(rollbackFor = Exception.class)
    @OperLog(module = "生产加工单", businessType = "VOID", saveParam = true)
    public void voidOrder(Long orderId) {
        permService.requirePerm("production:order:edit");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在");
        if (Constants.STATUS_FINISHED.equals(order.getBillStatus())) {
            throw BizException.of("已完工的生产单不能作废");
        }
        if (Constants.STATUS_CLOSED.equals(order.getBillStatus())) {
            throw BizException.of("该生产单已作废, 无需重复操作");
        }
        PrdOrder upd = new PrdOrder();
        upd.setId(orderId);
        upd.setBillStatus(Constants.STATUS_CLOSED);
        orderMapper.updateById(upd);
    }

    /**
     * 删除生产单 (只能删除草稿状态)
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long orderId) {
        permService.requirePerm("production:order:delete");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在");
        if (!Constants.STATUS_DRAFT.equals(order.getBillStatus())) {
            throw BizException.of("只能删除草稿状态的生产单");
        }
        // v1.1.52.4 修复: prd_order 唯一索引 uniq_prd_order_bill_no(bill_no, deleted) 只保证
        // "同一单号最多一条活行", 但允许多条历史软删行共价同 bill_no (deleted 都是 1).
        // 场景: 用户把 PD202609150005 删了 (id A, deleted=1), 又用同单号新建一条 (id B, deleted=0).
        // 再删 B 时逻辑删 B 会把 deleted 改成 1, 撞 (bill_no, 1) 唯一键 → DuplicateKeyException →
        // 前端误报 "数据已存在, 请检查编码/名称是否重复".
        // 修法: 软删 B 之前, 物理删掉同 bill_no 下所有其它软删历史行 (它们已是 tombstone,
        // 物理删是安全的), 腾出 (bill_no, 1) 槽位.
        orderMapper.delete(new LambdaQueryWrapper<PrdOrder>()
                .eq(PrdOrder::getBillNo, order.getBillNo())
                .ne(PrdOrder::getId, orderId)
                .eq(PrdOrder::getDeleted, 1));
        orderMapper.update(null, new LambdaUpdateWrapper<PrdOrder>()
                .eq(PrdOrder::getId, orderId).set(PrdOrder::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("生产加工单", String.valueOf(orderId), order, null);
    }
}