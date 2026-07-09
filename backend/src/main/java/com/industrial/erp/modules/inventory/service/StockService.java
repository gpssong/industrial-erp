package com.industrial.erp.modules.inventory.service;

import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
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

    public StockService(InvStockMapper stockMapper, InvLedgerMapper ledgerMapper, BaseProductMapper productMapper, RedisLock redisLock) {
        this.stockMapper = stockMapper;
        this.ledgerMapper = ledgerMapper;
        this.productMapper = productMapper;
        this.redisLock = redisLock;
    }

    private final InvStockMapper stockMapper;
    private final InvLedgerMapper ledgerMapper;
    private final BaseProductMapper productMapper;
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
        BigDecimal amount = price == null ? BigDecimal.ZERO : price.multiply(qty).setScale(4, RoundingMode.HALF_UP);

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
                s.setUnitId(unitId);
                s.setUnitName(unitName);
                s.setBatchNo(bn);
                s.setQty(qty);
                s.setAvailableQty(qty);
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
                BigDecimal newQty = (cur.getQty() == null ? BigDecimal.ZERO : cur.getQty()).add(qty);
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
            ledger.setUnitId(unitId);
            ledger.setUnitName(unitName);
            ledger.setBatchNo(bn);
            ledger.setQty(qty);
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
            if (stock.getQty().compareTo(qty) < 0) {
                throw BizException.of("库存不足, 商品=" + product.getProductName() + ", 当前库存=" + stock.getQty() + ", 需要=" + qty);
            }
            BigDecimal beforeQty = stock.getQty();
            BigDecimal beforeAvgCost = stock.getAvgCost() == null ? BigDecimal.ZERO : stock.getAvgCost();
            BigDecimal outCost = beforeAvgCost.multiply(qty).setScale(4, RoundingMode.HALF_UP);
            BigDecimal afterQty = beforeQty.subtract(qty);
            BigDecimal afterTotalCost = beforeAvgCost.multiply(afterQty).setScale(4, RoundingMode.HALF_UP);

            stock.setQty(afterQty);
            stock.setAvailableQty(afterQty);
            stock.setTotalCost(afterTotalCost);
            stock.setLastOutDate(LocalDate.now());
            stock.setUpdateTime(LocalDateTime.now());
            stockMapper.updateById(stock);

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
            ledger.setUnitId(unitId);
            ledger.setUnitName(unitName);
            ledger.setBatchNo(bn);
            ledger.setQty(qty);
            ledger.setPrice(beforeAvgCost);
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
}
