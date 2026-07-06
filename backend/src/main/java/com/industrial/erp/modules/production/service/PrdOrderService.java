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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

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

    public PrdOrderService(PrdOrderMapper orderMapper, PrdRequisitionMapper reqMapper, PrdRequisitionDetailMapper reqDetailMapper, PrdFinishedInMapper finishedInMapper, PrdBomService bomService, BaseProductMapper productMapper, BaseWarehouseMapper warehouseMapper, BillNoGenerator billNoGenerator, StockService stockService, PermissionService permService, OperLogPublisher operLogPublisher) {
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
        // 注入商品规格属性 (供前端表格 + 打印使用)
        res.getRecords().forEach(order -> {
            if (order.getProductId() != null) {
                BaseProduct prod = productMapper.selectById(order.getProductId());
                if (prod != null) {
                    order.setPThickness(prod.getThickness());
                    order.setPWidth(prod.getWidth());
                    order.setPDensity(prod.getDensity());
                    order.setPGramWeight(prod.getGramWeight());
                    order.setPMaterial(prod.getMaterial());
                }
            }
        });
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
            }
        }
        // 从 BOM 表取备注
        if (order != null && order.getBomId() != null) {
            PrdBom bom = bomService.detail(order.getBomId());
            if (bom != null && bom.getRemark() != null) {
                order.setBomRemark(bom.getRemark());
            }
        }
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="生产加工单", businessType="ADD", saveParam=true)
    public void add(PrdOrder order) {
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
     * 开工: 按 BOM 自动展开领料单
     */
    @Transactional(rollbackFor = Exception.class)
    public Long release(Long orderId) {
        permService.requirePerm("production:order:release");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在");
        if (!Constants.STATUS_DRAFT.equals(order.getBillStatus()) && !Constants.STATUS_RELEASED.equals(order.getBillStatus())) {
            throw BizException.of("当前状态不可开工");
        }
        if (order.getBomId() == null) throw BizException.of("未配置BOM, 无法展开领料单");

        PrdBom bom = bomService.detail(order.getBomId());
        if (bom == null || bom.getDetails() == null) throw BizException.of("BOM无明细");

        // 计算每种原料的实际用量 = baseQty * (planQty / bom.baseQty) * (1 + lossRate/100)
        BigDecimal ratio = order.getPlanQty().divide(bom.getBaseQty() == null ? BigDecimal.ONE : bom.getBaseQty(), 6, RoundingMode.HALF_UP);

        PrdRequisition req = new PrdRequisition();
        req.setBillNo(billNoGenerator.generate(Constants.BILL_RQ));
        req.setBillDate(LocalDate.now());
        req.setPrdOrderId(order.getId());
        req.setPrdOrderNo(order.getBillNo());
        req.setWarehouseId(order.getWarehouseId());
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
            BigDecimal need = base.multiply(ratio).multiply(BigDecimal.ONE.add(loss.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
            rd.setQty(need.setScale(4, RoundingMode.HALF_UP));
            req.getDetails().add(rd);
        }
        reqMapper.insert(req);
        for (PrdRequisitionDetail rd : req.getDetails()) {
            rd.setId(null);
            rd.setRequisitionId(req.getId());
            reqDetailMapper.insert(rd);
        }

        // 更新生产单状态
        PrdOrder upd = new PrdOrder();
        upd.setId(orderId);
        upd.setBillStatus(Constants.STATUS_RELEASED);
        orderMapper.updateById(upd);

        return req.getId();
    }

    /**
     * 成品入库
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(Long orderId, BigDecimal goodQty, BigDecimal lossQty, Long warehouseId) {
        permService.requirePerm("production:order:finish");
        PrdOrder order = orderMapper.selectById(orderId);
        if (order == null) throw BizException.of("生产单不存在");

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
        orderMapper.update(null, new LambdaUpdateWrapper<PrdOrder>()
                .eq(PrdOrder::getId, orderId).set(PrdOrder::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("生产加工单", String.valueOf(orderId), order, null);
    }
}