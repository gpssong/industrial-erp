package com.industrial.erp.modules.base.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BaseProductService {

    public BaseProductService(BaseProductMapper productMapper, BaseProductUnitMapper unitMapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.productMapper = productMapper;
        this.unitMapper = unitMapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final BaseProductMapper productMapper;
    private final BaseProductUnitMapper unitMapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<BaseProduct> page(Integer pageNum, Integer pageSize, String keyword, Long categoryId) {
        permService.requirePerm("base:product:list");
        Page<BaseProduct> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BaseProduct> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(BaseProduct::getProductCode, keyword)
                    .or().like(BaseProduct::getProductName, keyword)
                    .or().like(BaseProduct::getBarcode, keyword)
                    .or().like(BaseProduct::getSpec, keyword)
                    .or().like(BaseProduct::getMaterial, keyword));
        }
        if (categoryId != null) w.eq(BaseProduct::getCategoryId, categoryId);
        w.orderByDesc(BaseProduct::getId);
        return productMapper.selectPage(p, w);
    }

    /** App 端专用：不检查权限的商品查询 */
    public IPage<BaseProduct> pageWithoutPerm(Integer pageNum, Integer pageSize, String keyword) {
        Page<BaseProduct> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BaseProduct> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(BaseProduct::getProductCode, keyword)
                    .or().like(BaseProduct::getProductName, keyword)
                    .or().like(BaseProduct::getBarcode, keyword)
                    .or().like(BaseProduct::getSpec, keyword)
                    .or().like(BaseProduct::getMaterial, keyword));
        }
        w.orderByDesc(BaseProduct::getId);
        return productMapper.selectPage(p, w);
    }

    public Map<String, Object> detail(Long id) {
        BaseProduct p = productMapper.selectById(id);
        if (p == null) throw BizException.of("商品不存在");
        List<BaseProductUnit> units = unitMapper.selectByProductId(id);
        List<Map<String, Object>> stockSummary = productMapper.selectStockSummary(id);
        Map<String, Object> r = new HashMap<>();
        r.put("product", p);
        r.put("units", units);
        r.put("stockSummary", stockSummary);
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(BaseProduct p, List<BaseProductUnit> units) {
        permService.requirePerm("base:product:add");
        // 检查是否有同编码的正常记录
        BaseProduct exist = productMapper.selectByCode(p.getProductCode());
        if (exist != null) {
            throw BizException.of("商品编码已存在");
        }
        // 检查是否有软删除的同名记录，物理删除避免唯一索引冲突
        BaseProduct deleted = productMapper.selectAnyByCode(p.getProductCode());
        if (deleted != null) {
            productMapper.physicalDeleteById(deleted.getId()); // 物理删除
        }
        if (p.getStatus() == null) p.setStatus(1);
        // 从主单位同步价格到商品
        syncPriceFromMainUnit(p, units);
        productMapper.insert(p);
        saveUnits(p.getId(), units);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(BaseProduct p, List<BaseProductUnit> units) {
        permService.requirePerm("base:product:edit");
        BaseProduct origin = productMapper.selectById(p.getId());
        if (origin == null) throw BizException.of("商品不存在");
        // 从主单位同步价格到商品
        syncPriceFromMainUnit(p, units);
        productMapper.updateById(p);
        // 删除原单位
        unitMapper.delete(new LambdaQueryWrapper<BaseProductUnit>().eq(BaseProductUnit::getProductId, p.getId()));
        saveUnits(p.getId(), units);
    }

    public void delete(Long id) {
        permService.requirePerm("base:product:delete");
        BaseProduct p = productMapper.selectById(id);
        if (p == null) throw BizException.of("商品不存在");
        // 软删除主表 (add() 会在再次新增同编码时物理清理软删除墓碑, 避免唯一索引冲突)
        productMapper.update(null, new LambdaUpdateWrapper<BaseProduct>()
                .eq(BaseProduct::getId, id).set(BaseProduct::getDeleted, 1));
        // 软删除单位子表
        unitMapper.update(null, new LambdaUpdateWrapper<BaseProductUnit>()
                .eq(BaseProductUnit::getProductId, id).set(BaseProductUnit::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("商品管理", String.valueOf(id), p, null);
    }

    private void saveUnits(Long productId, List<BaseProductUnit> units) {
        if (units == null || units.isEmpty()) return;
        for (BaseProductUnit u : units) {
            u.setId(null);
            u.setProductId(productId);
            if (u.getUnitId() == null) u.setUnitId(0L);
            if (u.getConversionRate() == null) u.setConversionRate(BigDecimal.ONE);
            unitMapper.insert(u);
        }
    }

    private void syncPriceFromMainUnit(BaseProduct p, List<BaseProductUnit> units) {
        if (units == null || units.isEmpty()) return;
        for (BaseProductUnit u : units) {
            if (u.getIsMain() != null && u.getIsMain() == 1) {
                p.setSalesPrice(u.getSalesPrice());
                p.setCostPrice(u.getCostPrice());
                p.setPurchasePrice(u.getPurchasePrice());
                p.setMainUnitId(u.getUnitId());
                break;
            }
        }
    }

    /** 单位换算: qty(从单位) -> 主单位 */
    public BigDecimal convertToMain(Long productId, Long unitId, BigDecimal qty) {
        BaseProductUnit u = unitMapper.selectMainUnit(productId);
        if (u == null || u.getUnitId().equals(unitId)) return qty;
        BaseProductUnit source = unitMapper.selectByProductId(productId).stream()
                .filter(x -> x.getUnitId().equals(unitId)).findFirst().orElse(null);
        if (source == null) throw BizException.of("未找到对应单位");
        // qty 主 = qty_从 / conversionRate
        return qty.divide(source.getConversionRate(), 4, java.math.RoundingMode.HALF_UP);
    }
}
