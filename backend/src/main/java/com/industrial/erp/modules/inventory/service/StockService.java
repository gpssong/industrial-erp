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

/**
 * 库存核心服务
 * 关键能力:
 *   1. 入库: 移动加权平均成本计算
 *   2. 出库: 悲观锁 + 严格禁止负库存
 *   3. 库存台账写入
 */
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

    /**
     * 入库处理 (采购入库 / 成品入库 / 调拨入库 / 盘盈 / 初始化)
     * @param direction 1=入库
     */
    @Transactional(rollbackFor = Exception.class)
    public InvStock inStock(String billType, Long billId, String billNo, Long billDetailId,
                            Long warehouseId, String warehouseName, Long locationId, String locationName,
                            Long productId, Long unitId, String unitName, String batchNo,
                            BigDecimal qty, BigDecimal price, String sourceNo,
                            Long supplierId, Long customerId, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of("入库数量必须大于 0");
        }
        // 商品存在性
        BaseProduct product = productMapper.selectById(productId);
        if (product == null) throw BizException.of("商品不存在: " + productId);
        // 金额
        BigDecimal amount = price == null ? BigDecimal.ZERO : price.multiply(qty).setScale(4, RoundingMode.HALF_UP);

        // 分布式锁
        String key = Constants.REDIS_STOCK_LOCK + warehouseId + ":" + productId + ":" + (batchNo == null ? "" : batchNo);
        return redisLock.executeWithLock(key, 5, 30, () -> {
            // 直接 insert on duplicate update, 触发移动加权平均成本
            stockMapper.incrStock(warehouseId, warehouseName, locationId, locationName,
                    productId, product.getProductCode(), product.getProductName(),
                    product.getSpec(), unitId, unitName, batchNo,
                    qty, amount, LocalDate.now().toString());

            // 写台账
            InvStock cur = stockMapper.selectForUpdate(warehouseId, locationId, productId, batchNo);
            InvLedger ledger = new InvLedger();
            ledger.setBillType(billType);
            ledger.setBillId(billId);
            ledger.setBillNo(billNo);
            ledger.setBillDetailId(billDetailId);
            ledger.setBizDirection(Constants.DIRECTION_IN);
            ledger.setBizDate(LocalDate.now());
            ledger.setWarehouseId(warehouseId);
            ledger.setAreaId(cur == null ? null : cur.getAreaId());
            ledger.setLocationId(locationId);
            ledger.setProductId(productId);
            ledger.setProductCode(product.getProductCode());
            ledger.setProductName(product.getProductName());
            ledger.setUnitId(unitId);
            ledger.setUnitName(unitName);
            ledger.setBatchNo(batchNo);
            ledger.setQty(qty);
            ledger.setPrice(price);
            ledger.setAmount(amount);
            ledger.setBeforeQty(cur == null ? BigDecimal.ZERO : cur.getQty().subtract(qty));
            ledger.setAfterQty(cur == null ? qty : cur.getQty());
            ledger.setBeforeAvgCost(cur == null ? BigDecimal.ZERO : cur.getAvgCost());
            ledger.setAfterAvgCost(cur == null ? price : cur.getAvgCost());
            ledger.setSourceNo(sourceNo);
            ledger.setSupplierId(supplierId);
            ledger.setCustomerId(customerId);
            ledger.setRemark(remark);
            ledger.setCreateBy(SecurityContext.getUserId());
            ledgerMapper.insert(ledger);

            // 更新商品移动加权平均成本
            if (cur != null && cur.getAvgCost() != null && cur.getAvgCost().compareTo(BigDecimal.ZERO) > 0) {
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

            return cur == null ? stockMapper.selectForUpdate(warehouseId, locationId, productId, batchNo) : cur;
        });
    }

    /**
     * 出库处理 (销售出库 / 领料 / 调拨出库 / 盘亏)
     * @return 实际成本金额 (用于计算毛利)
     */
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

        String key = Constants.REDIS_STOCK_LOCK + warehouseId + ":" + productId + ":" + (batchNo == null ? "" : batchNo);
        return redisLock.executeWithLock(key, 5, 30, () -> {
            // 1. 行锁
            InvStock stock = stockMapper.selectForUpdate(warehouseId, locationId, productId, batchNo);
            if (stock == null) {
                throw BizException.of("库存不存在, 商品=" + product.getProductName() + ", 仓库=" + warehouseName);
            }
            // 2. 严格禁止负库存
            if (stock.getQty().compareTo(qty) < 0) {
                throw BizException.of("库存不足, 商品=" + product.getProductName() + ", 当前库存=" + stock.getQty() + ", 需要=" + qty);
            }
            BigDecimal beforeQty = stock.getQty();
            BigDecimal afterQty = beforeQty.subtract(qty);
            BigDecimal beforeCost = stock.getTotalCost();
            BigDecimal outCost = stock.getAvgCost().multiply(qty).setScale(4, RoundingMode.HALF_UP);
            BigDecimal afterCost = beforeCost.subtract(outCost);
            // 防成本负数
            if (afterCost.compareTo(BigDecimal.ZERO) < 0) afterCost = BigDecimal.ZERO;

            // 3. 更新库存
            stockMapper.updateQtyAndAvgCost(stock.getId(), afterQty, stock.getAvgCost(), afterCost, LocalDate.now().toString());

            // 4. 写台账
            InvLedger ledger = new InvLedger();
            ledger.setBillType(billType);
            ledger.setBillId(billId);
            ledger.setBillNo(billNo);
            ledger.setBillDetailId(billDetailId);
            ledger.setBizDirection(Constants.DIRECTION_OUT);
            ledger.setBizDate(LocalDate.now());
            ledger.setWarehouseId(warehouseId);
            ledger.setLocationId(locationId);
            ledger.setProductId(productId);
            ledger.setProductCode(product.getProductCode());
            ledger.setProductName(product.getProductName());
            ledger.setUnitId(unitId);
            ledger.setUnitName(unitName);
            ledger.setBatchNo(batchNo);
            ledger.setQty(qty);
            ledger.setPrice(stock.getAvgCost());
            ledger.setAmount(outCost);
            ledger.setBeforeQty(beforeQty);
            ledger.setAfterQty(afterQty);
            ledger.setBeforeAvgCost(stock.getAvgCost());
            ledger.setAfterAvgCost(stock.getAvgCost());
            ledger.setSourceNo(sourceNo);
            ledger.setSupplierId(supplierId);
            ledger.setCustomerId(customerId);
            ledger.setRemark(remark);
            ledger.setCreateBy(SecurityContext.getUserId());
            ledgerMapper.insert(ledger);

            return outCost;
        });
    }
}
