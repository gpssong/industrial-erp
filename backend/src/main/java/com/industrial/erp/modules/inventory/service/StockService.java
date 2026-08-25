package com.industrial.erp.modules.inventory.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.inventory.entity.InvLedger;
import com.industrial.erp.modules.inventory.entity.InvStock;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.utils.RedisLock;
import com.industrial.erp.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class StockService {

    public StockService(InvStockMapper stockMapper, InvLedgerMapper ledgerMapper, BaseProductMapper productMapper, BaseProductUnitMapper unitMapper, RedisLock redisLock) {
        this.stockMapper = stockMapper;
        this.ledgerMapper = ledgerMapper;
        this.productMapper = productMapper;
        this.unitMapper = unitMapper;
        this.redisLock = redisLock;
    }

    private final InvStockMapper stockMapper;
    private final InvLedgerMapper ledgerMapper;
    private final BaseProductMapper productMapper;
    private final BaseProductUnitMapper unitMapper;
    private final RedisLock redisLock;

    @Transactional(rollbackFor = Exception.class)
    public InvStock inStock(String billType, Long billId, String billNo, Long billDetailId,
                            Long warehouseId, String warehouseName, Long locationId, String locationName,
                            Long productId, Long unitId, String unitName, String batchNo,
                            BigDecimal qty, BigDecimal price, String sourceNo,
                            Long supplierId, Long customerId, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of("入库数量必须大于 0");
        }
        BaseProduct product = productMapper.selectById(productId);
        if (product == null) throw BizException.of("商品不存在: " + productId);

        // v1.1.17+: 副单位录入 → 主单位折算 (1副单位 = conversion_rate 主单位).
        // 库存与台账内部统一按主单位存. 单据明细 (SalDeliveryDetail 等) 的 qty/unitName 保持原值不变.
        BaseProductUnit mainUnit = unitMapper.selectMainUnit(productId);
        Long mainUnitId = mainUnit == null ? unitId : mainUnit.getUnitId();
        String mainUnitName = mainUnit == null ? unitName : mainUnit.getUnitName();
        BigDecimal mainQty = convertToMain(productId, unitId, qty, mainUnit);

        BigDecimal amount = price == null ? BigDecimal.ZERO : price.multiply(mainQty).setScale(4, RoundingMode.HALF_UP);

        // v1.1.7+: 空串统一归一为 null, 避免前端 "" 与 后端 null OGNL 行为不一致.
        String bn = (batchNo == null || batchNo.isEmpty()) ? null : batchNo;

        String key = Constants.REDIS_STOCK_LOCK + warehouseId + ":" + productId + ":" + (bn == null ? "" : bn);
        return redisLock.executeWithLock(key, 5, 30, () -> {
            InvStock cur = stockMapper.selectForUpdate(warehouseId, productId, bn);
            // 操作前的快照, 用于台账. 新建库存场景默认 0.
            BigDecimal beforeQtyIn = cur == null || cur.getQty() == null ? BigDecimal.ZERO : cur.getQty();
            BigDecimal beforeAvgCostIn = cur == null || cur.getAvgCost() == null ? BigDecimal.ZERO : cur.getAvgCost();
            if (cur == null) {
                // 新增库存
                InvStock s = new InvStock();
                s.setWarehouseId(warehouseId);
                s.setWarehouseName(warehouseName);
                s.setLocationId(locationId);
                s.setLocationName(locationName);
                s.setProductId(productId);
                s.setProductCode(product.getProductCode());
                s.setProductName(product.getProductName());
                s.setSpec(product.getSpec());
                s.setUnitId(mainUnitId);
                s.setUnitName(mainUnitName);
                s.setBatchNo(bn);
                s.setQty(mainQty);
                s.setAvailableQty(mainQty);
                s.setAvgCost(price != null ? price : BigDecimal.ZERO);
                s.setTotalCost(amount);
                s.setLastInDate(LocalDate.now());
                s.setCreateTime(LocalDateTime.now());
                s.setUpdateTime(LocalDateTime.now());
                s.setDeleted(0);
                stockMapper.insert(s);
                cur = s;
            } else {
                // 更新现有库存: 移动加权平均
                BigDecimal newTotalCost = (cur.getTotalCost() == null ? BigDecimal.ZERO : cur.getTotalCost()).add(amount);
                BigDecimal newQty = (cur.getQty() == null ? BigDecimal.ZERO : cur.getQty()).add(mainQty);
                BigDecimal newAvgCost = newQty.compareTo(BigDecimal.ZERO) > 0 ? newTotalCost.divide(newQty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                cur.setQty(newQty);
                cur.setAvailableQty(newQty);
                cur.setTotalCost(newTotalCost);
                cur.setAvgCost(newAvgCost);
                cur.setLastInDate(LocalDate.now());
                cur.setUpdateTime(LocalDateTime.now());
                stockMapper.updateById(cur);
            }

            // 写台账
            InvLedger ledger = new InvLedger();
            ledger.setBillType(billType);
            ledger.setBillId(billId);
            ledger.setBillNo(billNo);
            ledger.setBillDetailId(billDetailId);
            ledger.setBizDirection(Constants.DIRECTION_IN);
            ledger.setBizDate(LocalDate.now());
            ledger.setWarehouseId(warehouseId);
            ledger.setAreaId(cur.getAreaId());
            ledger.setLocationId(locationId);
            ledger.setProductId(productId);
            ledger.setProductCode(product.getProductCode());
            ledger.setProductName(product.getProductName());
            ledger.setSpec(product.getSpec());
            ledger.setModel(product.getModel());
            ledger.setUnitId(mainUnitId);
            ledger.setUnitName(mainUnitName);
            ledger.setBatchNo(bn);
            ledger.setQty(mainQty);
            ledger.setPrice(price);
            ledger.setAmount(amount);
            // 修复: 这里曾直接读 cur.getQty()/getAvgCost(), 但上方已 setQty/setAvgCost 把 cur 改成新值,
            // 导致台账的 before_qty/before_avg_cost 显示的是"操作后"的值 (与 after 相同), 库存台账失真.
            // 改用 lambda 入口处缓存的 beforeQtyIn / beforeAvgCostIn.
            ledger.setBeforeQty(beforeQtyIn);
            ledger.setAfterQty(cur.getQty());
            ledger.setBeforeAvgCost(beforeAvgCostIn);
            ledger.setAfterAvgCost(cur.getAvgCost());
            ledger.setSourceNo(sourceNo);
            ledger.setSupplierId(supplierId);
            ledger.setCustomerId(customerId);
            ledger.setRemark(remark);
            ledger.setCreateBy(SecurityContext.getUserId());
            ledger.setDeleted(0);
            ledgerMapper.insert(ledger);

            // 更新商品移动加权平均成本
            if (cur.getAvgCost() != null && cur.getAvgCost().compareTo(BigDecimal.ZERO) > 0) {
                BaseProduct p = new BaseProduct();
                p.setId(productId);
                p.setCostPrice(cur.getAvgCost());
                p.setUpdateBy(SecurityContext.getUserId());
                productMapper.updateById(p);
            } else if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                BaseProduct p = new BaseProduct();
                p.setId(productId);
                p.setCostPrice(price);
                p.setUpdateBy(SecurityContext.getUserId());
                productMapper.updateById(p);
            }

            return cur;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public BigDecimal outStock(String billType, Long billId, String billNo, Long billDetailId,
                               Long warehouseId, String warehouseName, Long locationId, String locationName,
                               Long productId, Long unitId, String unitName, String batchNo,
                               BigDecimal qty, BigDecimal price, String sourceNo,
                               Long supplierId, Long customerId, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of("出库数量必须大于 0");
        }
        BaseProduct product = productMapper.selectById(productId);
        if (product == null) throw BizException.of("商品不存在: " + productId);

        // v1.1.17+: 副单位录入 → 主单位折算 (1副单位 = conversion_rate 主单位).
        BaseProductUnit mainUnit = unitMapper.selectMainUnit(productId);
        Long mainUnitId = mainUnit == null ? unitId : mainUnit.getUnitId();
        String mainUnitName = mainUnit == null ? unitName : mainUnit.getUnitName();
        BigDecimal mainQty = convertToMain(productId, unitId, qty, mainUnit);

        // v1.1.7+: 空串统一归一为 null, 避免前端 "" 与 后端 null OGNL 行为不一致.
        String bn = (batchNo == null || batchNo.isEmpty()) ? null : batchNo;

        String key = Constants.REDIS_STOCK_LOCK + warehouseId + ":" + productId + ":" + (bn == null ? "" : bn);
        return redisLock.executeWithLock(key, 5, 30, () -> {
            InvStock stock = stockMapper.selectForUpdate(warehouseId, productId, bn);
            if (stock == null) {
                // 列一下该仓库+商品所有批次库存, 帮用户定位"出库批次号 与 库存批次号不一致"
                List<InvStock> candidates = stockMapper.listByWarehouseAndProduct(warehouseId, productId);
                String detail = candidates.isEmpty()
                    ? "(该仓库无该商品任何库存记录, 请先录入或采购入库)"
                    : candidates.stream()
                        .map(s -> {
                            String sBatch = s.getBatchNo() == null ? "<无批次>" : s.getBatchNo();
                            return String.format("[批次=%s, 库存=%s]", sBatch, s.getQty());
                        })
                        .reduce((a, b) -> a + ", " + b).orElse("");
                throw BizException.of(String.format(
                    "库存不存在, 商品=%s(ID=%d), 仓库=%s(ID=%d), 入参批次=%s. %s",
                    product.getProductName(), productId, warehouseName, warehouseId,
                    bn == null ? "<无>" : bn, detail));
            }
            if (stock.getQty().compareTo(mainQty) < 0) {
                throw BizException.of("库存不足, 商品=" + product.getProductName() + ", 当前库存=" + stock.getQty() + ", 需要=" + mainQty);
            }
            BigDecimal beforeQty = stock.getQty();
            BigDecimal beforeAvgCost = stock.getAvgCost() == null ? BigDecimal.ZERO : stock.getAvgCost();
            BigDecimal outCost = beforeAvgCost.multiply(mainQty).setScale(4, RoundingMode.HALF_UP);
            BigDecimal afterQty = beforeQty.subtract(mainQty);
            BigDecimal afterTotalCost = beforeAvgCost.multiply(afterQty).setScale(4, RoundingMode.HALF_UP);

            // v1.1.20+ P0-2: 乐观锁守卫, WHERE version=#{version} 防并发覆盖
            // InvStock 已有 @Version 字段, MyBatis-Plus 自动注入 version 到 UPDATE
            stock.setQty(afterQty);
            stock.setAvailableQty(afterQty);
            stock.setTotalCost(afterTotalCost);
            stock.setLastOutDate(LocalDate.now());
            stock.setUpdateTime(LocalDateTime.now());
            int rows = stockMapper.updateById(stock);
            if (rows == 0) {
                throw BizException.of(String.format(
                    "库存不足或版本冲突, 商品=%s(ID=%d), 仓库=%s(ID=%d), 需要=%s",
                    product.getProductName(), productId, warehouseName, warehouseId, mainQty));
            }

            // 写台账
            InvLedger ledger = new InvLedger();
            ledger.setBillType(billType);
            ledger.setBillId(billId);
            ledger.setBillNo(billNo);
            ledger.setBillDetailId(billDetailId);
            ledger.setBizDirection(Constants.DIRECTION_OUT);
            ledger.setBizDate(LocalDate.now());
            ledger.setWarehouseId(warehouseId);
            ledger.setWarehouseName(warehouseName);
            ledger.setLocationId(locationId);
            ledger.setProductId(productId);
            ledger.setProductCode(product.getProductCode());
            ledger.setProductName(product.getProductName());
            ledger.setSpec(product.getSpec());
            ledger.setModel(product.getModel());
            ledger.setUnitId(mainUnitId);
            ledger.setUnitName(mainUnitName);
            ledger.setBatchNo(bn);
            ledger.setQty(mainQty);
            ledger.setPrice(price);
            ledger.setAmount(outCost);
            ledger.setBeforeQty(beforeQty);
            ledger.setAfterQty(afterQty);
            ledger.setBeforeAvgCost(beforeAvgCost);
            ledger.setAfterAvgCost(beforeAvgCost);
            ledger.setSourceNo(sourceNo);
            ledger.setSupplierId(supplierId);
            ledger.setCustomerId(customerId);
            ledger.setRemark(remark);
            ledger.setCreateBy(SecurityContext.getUserId());
            ledger.setDeleted(0);
            ledgerMapper.insert(ledger);

            return outCost;
        });
    }

    /** v1.1.17+: 副单位 → 主单位折算 (qty_主 = qty_从 * conversion_rate).
     *  主单位时直接返回 qty; 找不到单位时返回 qty (兜底, 保持历史行为不变 — 不让脏数据 throw 中断业务).
     *  接收已查好的 mainUnit 以避免在循环中重复 selectMainUnit. */
    private BigDecimal convertToMain(Long productId, Long unitId, BigDecimal qty, BaseProductUnit mainUnit) {
        if (qty == null) return BigDecimal.ZERO;
        if (unitId == null) return qty;
        if (mainUnit == null) return qty;
        if (mainUnit.getUnitId().equals(unitId)) return qty;
        BaseProductUnit source = unitMapper.selectByProductId(productId).stream()
                .filter(x -> x.getUnitId().equals(unitId)).findFirst().orElse(null);
        if (source == null) return qty;
        return qty.multiply(source.getConversionRate()).setScale(4, RoundingMode.HALF_UP);
    }
}
