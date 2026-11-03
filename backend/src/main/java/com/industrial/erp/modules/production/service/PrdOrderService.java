package com.industrial.erp.modules.production.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdOrderMaterial;
import com.industrial.erp.modules.production.entity.PrdRequisition;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMaterialMapper;
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
import java.util.HashMap;
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
    /** v1.1.142: 规格/单位 派生字段兜底回填的唯一口径. */
    private final ProductDeriveService deriveService;
    /** v1.1.160: 工单原料明细 (BOM 逐行 + 每行出库仓/库存批号). */
    private final PrdOrderMaterialMapper orderMaterialMapper;
    /** v1.1.160: 回显原料行选定批次的可用库存量. */
    private final com.industrial.erp.modules.inventory.mapper.InvStockMapper invStockMapper;

    public PrdOrderService(PrdOrderMapper orderMapper, PrdRequisitionMapper reqMapper, PrdRequisitionDetailMapper reqDetailMapper, PrdFinishedInMapper finishedInMapper, PrdBomService bomService, BaseProductMapper productMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService, OperLogPublisher operLogPublisher, com.industrial.erp.modules.mes.service.MesReportService mesReportService, ProductDeriveService deriveService, PrdOrderMaterialMapper orderMaterialMapper, com.industrial.erp.modules.inventory.mapper.InvStockMapper invStockMapper) {
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
        this.deriveService = deriveService;
        this.orderMaterialMapper = orderMaterialMapper;
        this.invStockMapper = invStockMapper;
    }

    /**
     * v1.1.133 总重量 (kg) = 计划数量(件) × 成品克重(g/件) ÷ 1000.
     * 唯一算法出口: 详情/列表展示与 release() 领料展开都走这里, 防止两处口径漂移.
     * 缺数量或克重 (克重 ≤0 视为未填) → null, 调用方按降级处理.
     */
    static BigDecimal totalWeightKg(BigDecimal planQty, BigDecimal gramWeight) {
        if (planQty == null || gramWeight == null || gramWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return planQty.multiply(gramWeight).divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
    }

    /** 展示口径: 3 位小数 (kg); 领料展开仍用 6 位原值 */
    private static BigDecimal display(BigDecimal kg) {
        return kg == null ? null : kg.setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * v1.1.160 BOM 单行领料量 (kg) 唯一算法出口: 开工 release() 与前端「按 BOM 展开原料行」的
     * 建议接口 material-suggest 都走这里, 保证屏幕上看到的需求数量与开工生成的领料数量分毫不差
     * (v1.1.119 口径: 用量 = 成品总重 × 本项配比/Σ配比 × (1 + 本项损耗率/100)).
     * 缺总量或配比合计 ≤0 → null, 调用方按降级处理 (与 totalWeightKg 同一风格).
     */
    static BigDecimal bomLineNeedKg(BigDecimal totalKg, BigDecimal baseQty, BigDecimal sumRatio, BigDecimal lossRate) {
        if (totalKg == null || sumRatio == null || sumRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal base = baseQty == null ? BigDecimal.ZERO : baseQty;
        BigDecimal loss = lossRate == null ? BigDecimal.ZERO : lossRate;
        BigDecimal share = base.divide(sumRatio, 10, RoundingMode.HALF_UP);
        return totalKg
                .multiply(share)
                .multiply(BigDecimal.ONE.add(loss.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP);
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
        // v1.1.142: 一次批量取主单位, 供下方 规格/单位 兜底回填 (别在循环里逐行查)
        Map<Long, BaseProductUnit> mainUnitById = deriveService.mainUnits(productIds);
        for (PrdOrder order : res.getRecords()) {
            BaseProduct prod = order.getProductId() == null ? null : prodById.get(order.getProductId());
            BaseProductUnit mainUnit = order.getProductId() == null ? null : mainUnitById.get(order.getProductId());
            if (prod != null) {
                order.setPThickness(prod.getThickness());
                order.setPWidth(prod.getWidth());
                order.setPDensity(prod.getDensity());
                order.setPGramWeight(prod.getGramWeight());
                order.setPMaterial(prod.getMaterial());
                order.setPColorNo(prod.getColorNo());
                order.setTotalWeightKg(display(totalWeightKg(order.getPlanQty(), prod.getGramWeight())));
            }
            backfillDerived(order, prod, mainUnit);
        }
        return res;
    }

    /**
     * v1.1.142: 工单上的 规格/单位 是开单时从商品拷的快照, 老单经常是空 (单位历史上压根没写过,
     * 规格则因 base_product.spec 未填而空 —— 实际规格在 base_product.model 里).
     * 快照空时用商品主数据兜底 (规格: 商品 spec → 商品 model; 单位: base_product_unit 主单位),
     * 已有快照一律不动。App/PC 的「成品入库」页直接消费这份快照, 不兜底就会显示「该商品未配置单位」,
     * 并把 NULL 单位一路带进入库单与库存流水。
     */
    private void backfillDerived(PrdOrder order, BaseProduct prod, BaseProductUnit mainUnit) {
        if (order == null) return;
        if (StrUtil.isBlank(order.getSpec())) {
            String spec = deriveService.spec(order.getSpec(), prod);
            if (StrUtil.isNotBlank(spec)) order.setSpec(spec);
        }
        if (prod != null) {
            if (StrUtil.isBlank(order.getProductCode())) order.setProductCode(prod.getProductCode());
            if (StrUtil.isBlank(order.getProductName())) order.setProductName(prod.getProductName());
        }
        if (!deriveService.hasUnit(order.getUnitName()) && mainUnit != null) {
            order.setUnitName(mainUnit.getUnitName());
            if (mainUnit.getUnitId() != null && mainUnit.getUnitId() > 0L) order.setUnitId(mainUnit.getUnitId());
        }
    }

    public PrdOrder detail(Long id) {
        PrdOrder order = orderMapper.selectById(id);
        // JOIN 注入商品规格属性 + BOM 备注 (打印模板用)
        BaseProduct p = null;
        if (order != null && order.getProductId() != null) {
            p = productMapper.selectById(order.getProductId());
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
        // v1.1.142: 规格/单位 快照为空时从商品主数据兜底 (与 page() 同一口径)
        backfillDerived(order, p, order == null ? null : deriveService.mainUnit(order.getProductId()));
        // 加载领料明细 (生产加工单打印用: 跨所有领料单汇总明细行)
        if (order != null) {
            // v1.1.133 总重量 (kg) = 计划数量 × 克重 ÷ 1000, 生产单详情展示 + 领料合计口径
            order.setTotalWeightKg(display(totalWeightKg(order.getPlanQty(), order.getPGramWeight())));
            order.setRequisitionDetails(reqDetailMapper.selectByPrdOrderId(id));
            // v1.1.160: 回显工单原料明细 + 每行选定批次的可用库存量
            order.setMaterials(loadMaterials(id));
        }
        return order;
    }

    /** v1.1.160: 载工单原料行, 并给已选批次的行注入该 (仓库, 商品, 批次) 的可用库存量 (供前端提示是否够领). */
    private List<PrdOrderMaterial> loadMaterials(Long orderId) {
        List<PrdOrderMaterial> list = orderMaterialMapper.selectByOrderId(orderId);
        if (list == null || list.isEmpty()) return java.util.Collections.emptyList();
        for (PrdOrderMaterial m : list) {
            if (m.getWarehouseId() != null && m.getProductId() != null) {
                try {
                    com.industrial.erp.modules.inventory.entity.InvStock s =
                            invStockMapper.selectOne(new LambdaQueryWrapper<com.industrial.erp.modules.inventory.entity.InvStock>()
                                    .eq(com.industrial.erp.modules.inventory.entity.InvStock::getWarehouseId, m.getWarehouseId())
                                    .eq(com.industrial.erp.modules.inventory.entity.InvStock::getProductId, m.getProductId())
                                    .eq(StrUtil.isNotBlank(m.getBatchNo()),
                                            com.industrial.erp.modules.inventory.entity.InvStock::getBatchNo, m.getBatchNo())
                                    .isNull(StrUtil.isBlank(m.getBatchNo()),
                                            com.industrial.erp.modules.inventory.entity.InvStock::getBatchNo)
                                    .eq(com.industrial.erp.modules.inventory.entity.InvStock::getDeleted, 0)
                                    .last("LIMIT 1"));
                    if (s != null) m.setAvailableQty(s.getAvailableQty());
                } catch (Exception ignore) {
                    // 库存查询失败不影响详情回显 (availableQty 仅提示用)
                }
            }
        }
        return list;
    }

    /**
     * v1.1.160: 整体替换工单原料明细 (软删旧行 + 插新行).
     * materials 为 null/空 → 只清不建 (未选 BOM 或全部删除). 每行必须带 productId.
     */
    private void saveMaterials(Long orderId, List<PrdOrderMaterial> materials) {
        // MyBatis-Plus @TableLogic: delete(wrapper) 即软删该工单全部原料行 (deleted=0 → 1)
        orderMaterialMapper.delete(new LambdaQueryWrapper<PrdOrderMaterial>()
                .eq(PrdOrderMaterial::getPrdOrderId, orderId));
        if (materials == null || materials.isEmpty()) return;
        int line = 0;
        for (PrdOrderMaterial m : materials) {
            if (m == null || m.getProductId() == null) continue;   // 无原料商品的行不落
            m.setId(null);
            m.setPrdOrderId(orderId);
            m.setLineNo(++line);
            m.setDeleted(0);
            orderMaterialMapper.insert(m);
        }
    }

    /**
     * v1.1.160: App 现场开工确认「批号从库存选择」—— 只把每行的出库仓库 + 批号贴到已有原料行.
     *
     * <p>入参只读 productId/warehouseId/batchNo 三个字段, 需求量/品名等一律忽略:
     * 现场工人不该能改 BOM 算出来的领料量 (口径仍归 release() 与 bomLineNeedKg).
     * <p>老单 (v1.1.160 之前建的、没走过 PC 新弹窗) 没有原料行时先按当前 BOM 展开一份再贴批次,
     * 这样开工生成的领料单照样带得上批号 —— 否则领料审核照旧卡在「库存不存在」.
     *
     * @return 实际写入批次的原料行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int applyMaterialBatches(Long orderId, List<PrdOrderMaterial> picks) {
        if (picks == null || picks.isEmpty()) return 0;
        Map<Long, PrdOrderMaterial> byProduct = new HashMap<>();
        for (PrdOrderMaterial p : picks) {
            if (p == null || p.getProductId() == null) continue;
            byProduct.put(p.getProductId(), p);
        }
        if (byProduct.isEmpty()) return 0;

        List<PrdOrderMaterial> rows = orderMaterialMapper.selectByOrderId(orderId);
        if (rows == null || rows.isEmpty()) {
            PrdOrder order = orderMapper.selectById(orderId);
            if (order == null) return 0;
            BaseProduct product = order.getProductId() == null ? null : productMapper.selectById(order.getProductId());
            Long bomId = order.getBomId() != null ? order.getBomId()
                    : (product == null ? null : product.getBomId());
            List<PrdOrderMaterial> expanded = expandBom(bomId, order.getPlanQty(),
                    product == null ? null : product.getGramWeight());
            if (expanded.isEmpty()) return 0;
            saveMaterials(orderId, expanded);
            rows = orderMaterialMapper.selectByOrderId(orderId);
        }

        int patched = 0;
        for (PrdOrderMaterial row : rows) {
            PrdOrderMaterial pick = row.getProductId() == null ? null : byProduct.get(row.getProductId());
            if (pick == null) continue;
            String batchNo = StrUtil.isBlank(pick.getBatchNo()) ? null : pick.getBatchNo().trim();
            if (pick.getWarehouseId() == null && batchNo == null) continue;   // 没选就不动, 别把已存的清掉
            row.setWarehouseId(pick.getWarehouseId());
            row.setBatchNo(batchNo);
            orderMaterialMapper.updateById(row);
            patched++;
        }
        return patched;
    }

    /**
     * v1.1.160: 新建/编辑工单时把成品 BOM 展开成原料行, 并带出每行可选的库存批次 (跨仓库, 可用量 &gt; 0).
     * <p>BOM 解析口径与 add() 一致 (成品 base_product.bom_id → bomService.detail); 需求数量走
     * {@link #bomLineNeedKg}, 与开工 release() 生成的领料数量同一算法 —— 屏幕上看到的就是领料单上的。
     * <p>返回的行就是前端要提交的行 (字段同名), 前端只需再填每行的 warehouseId + batchNo。
     * <p>降级返回空列表 (不抛错): 成品不存在 / 未配 BOM / BOM 无明细 / 缺克重或配比合计为 0,
     * 与开工那套「无 BOM 不阻塞」保持同一风格, 建单照常, 用户后续手动建领料单。
     */
    public List<PrdOrderMaterial> suggestMaterials(Long productId, BigDecimal planQty) {
        if (productId == null) return java.util.Collections.emptyList();
        BaseProduct p = productMapper.selectById(productId);
        if (p == null || p.getBomId() == null) return java.util.Collections.emptyList();
        return expandBom(p.getBomId(), planQty, p.getGramWeight());
    }

    /**
     * v1.1.160: 把一张 BOM 按 planQty 展开成原料行 (需求数量走 bomLineNeedKg) + 每行的可用库存批次候选.
     * <p>bomId 由调用方定: 新建工单走成品的 base_product.bom_id, 老工单补批次走工单自己存的 prd_order.bom_id
     * (两者在 add() 里会对齐, 但历史单可能手选过别的 BOM —— 以工单为准才和 release() 生成的一致).
     */
    private List<PrdOrderMaterial> expandBom(Long bomId, BigDecimal planQty, BigDecimal gramWeight) {
        if (bomId == null) return java.util.Collections.emptyList();
        PrdBom bom = bomService.detail(bomId);
        if (bom == null || bom.getDetails() == null || bom.getDetails().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        BigDecimal sumRatio = BigDecimal.ZERO;
        for (PrdBomDetail bd : bom.getDetails()) {
            sumRatio = sumRatio.add(bd.getBaseQty() == null ? BigDecimal.ZERO : bd.getBaseQty());
        }
        // 成品总重 (kg) = 计划数量 × 克重 ÷ 1000; 缺克重或未传数量 → 算不出用量, 只给行不给数
        BigDecimal totalKg = totalWeightKg(planQty, gramWeight);
        boolean computable = totalKg != null && sumRatio.compareTo(BigDecimal.ZERO) > 0;

        List<PrdOrderMaterial> rows = new java.util.ArrayList<>(bom.getDetails().size());
        int line = 0;
        for (PrdBomDetail bd : bom.getDetails()) {
            PrdOrderMaterial m = new PrdOrderMaterial();
            m.setLineNo(++line);
            m.setBomDetailId(bd.getId());
            m.setMaterialType(bd.getMaterialType());
            m.setProductId(bd.getProductId());
            m.setProductCode(bd.getProductCode());
            m.setProductName(bd.getProductName());
            m.setSpec(bd.getSpec());
            m.setUnitId(bd.getUnitId());
            m.setUnitName(bd.getUnitName());
            m.setBomBaseQty(bd.getBaseQty());
            m.setLossRate(bd.getLossRate());
            m.setReqQty(computable ? bomLineNeedKg(totalKg, bd.getBaseQty(), sumRatio, bd.getLossRate()) : null);
            m.setBatches(stockBatchOptions(bd.getProductId()));
            rows.add(m);
        }
        return rows;
    }

    /** v1.1.160: 某原料当前可用库存批次候选 (available_qty &gt; 0, 跨仓库, 按入库先后 = FIFO). */
    private List<com.industrial.erp.modules.production.vo.StockBatchOptionVO> stockBatchOptions(Long materialProductId) {
        if (materialProductId == null) return java.util.Collections.emptyList();
        List<com.industrial.erp.modules.inventory.entity.InvStock> stocks =
                invStockMapper.listAvailableByProduct(materialProductId);
        if (stocks == null || stocks.isEmpty()) return java.util.Collections.emptyList();
        List<com.industrial.erp.modules.production.vo.StockBatchOptionVO> out =
                new java.util.ArrayList<>(stocks.size());
        for (com.industrial.erp.modules.inventory.entity.InvStock s : stocks) {
            com.industrial.erp.modules.production.vo.StockBatchOptionVO o =
                    new com.industrial.erp.modules.production.vo.StockBatchOptionVO();
            o.setWarehouseId(s.getWarehouseId());
            o.setWarehouseName(s.getWarehouseName());
            o.setBatchNo(s.getBatchNo());
            o.setUnitId(s.getUnitId());
            o.setUnitName(s.getUnitName());
            o.setAvailableQty(s.getAvailableQty());
            o.setProductionDate(s.getProductionDate());
            o.setExpireDate(s.getExpireDate());
            out.add(o);
        }
        return out;
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
        // v1.1.160: 落工单原料明细 (BOM 逐行 + 每行出库仓/库存批号), 供开工回填领料行批次
        saveMaterials(order.getId(), order.getMaterials());
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
        // v1.1.160: 前端提交了 materials 才整体替换 (null = 未改动原料区, 保持原行)
        if (order.getMaterials() != null) {
            saveMaterials(order.getId(), order.getMaterials());
        }
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
                // 成品总重: planQty(件) × 克重(g/件) ÷ 1000 → kg  (v1.1.133 与详情展示的「总重量」同一算法)
                BigDecimal totalKg = totalWeightKg(order.getPlanQty(), gram);

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
                    // v1.1.160: 工单原料明细按 productId 建索引, 开工时把用户选好的批次/出库仓回填到领料行.
                    // (同一原料在 BOM 里通常只一行; 多行同 productId 时取第一行批次.)
                    Map<Long, PrdOrderMaterial> matByProduct = new HashMap<>();
                    for (PrdOrderMaterial m : loadMaterials(order.getId())) {
                        if (m.getProductId() != null) matByProduct.putIfAbsent(m.getProductId(), m);
                    }
                    int line = 0;
                    for (PrdBomDetail bd : bom.getDetails()) {
                        PrdRequisitionDetail rd = new PrdRequisitionDetail();
                        rd.setLineNo(++line);
                        rd.setProductId(bd.getProductId());
                        rd.setProductCode(bd.getProductCode());
                        rd.setProductName(bd.getProductName());
                        rd.setUnitId(bd.getUnitId());
                        rd.setUnitName(bd.getUnitName());
                        // v1.1.160: 需求数量口径抽到 bomLineNeedKg, 与前端建议接口共用同一算法
                        rd.setQty(bomLineNeedKg(totalKg, bd.getBaseQty(), sumRatio, bd.getLossRate()));
                        // v1.1.160: 回填批次 + 出库仓 (领料审核 outStock 需精确命中 inv_stock 行, 否则报"库存不存在")
                        PrdOrderMaterial mat = bd.getProductId() == null ? null : matByProduct.get(bd.getProductId());
                        if (mat != null) {
                            if (StrUtil.isNotBlank(mat.getBatchNo())) rd.setBatchNo(mat.getBatchNo());
                            if (mat.getWarehouseId() != null) rd.setWarehouseId(mat.getWarehouseId());
                        }
                        req.getDetails().add(rd);
                    }
                    // v1.1.133 把口径写在单上: 领料合计应当等于生产单详情里的「总重量」
                    // (损耗率 >0 的 BOM 会略高于总重量, 那是补投料, 不是算错)
                    BigDecimal sumQty = BigDecimal.ZERO;
                    for (PrdRequisitionDetail rd : req.getDetails()) {
                        sumQty = sumQty.add(rd.getQty());
                    }
                    req.setRemark("按 BOM 配比份数分摊成品总重量 " + display(totalKg).toPlainString()
                            + " kg (= 计划数量 × 克重 ÷ 1000); 本单领料合计 " + sumQty.setScale(3, RoundingMode.HALF_UP).toPlainString() + " kg");
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
        // v1.1.160: 工单软删 → 原料明细一并软删 (只删未删的活行)
        orderMaterialMapper.delete(new LambdaQueryWrapper<PrdOrderMaterial>()
                .eq(PrdOrderMaterial::getPrdOrderId, orderId));
        operLogPublisher.publishDeleteSnapshot("生产加工单", String.valueOf(orderId), order, null);
    }
}