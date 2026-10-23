package com.industrial.erp.modules.production.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseProductUnitMapper;
import com.industrial.erp.modules.production.mapper.PrdBomDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdBomMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PrdBomService {

    public PrdBomService(PrdBomMapper bomMapper, PrdBomDetailMapper detailMapper, BaseProductMapper productMapper, BaseProductUnitMapper unitMapper, PermissionService permService, BillNoGenerator billNoGenerator, OperLogPublisher operLogPublisher) {
        this.bomMapper = bomMapper;
        this.detailMapper = detailMapper;
        this.productMapper = productMapper;
        this.unitMapper = unitMapper;
        this.permService = permService;
        this.billNoGenerator = billNoGenerator;
        this.operLogPublisher = operLogPublisher;
    }

    private final PrdBomMapper bomMapper;
    private final PrdBomDetailMapper detailMapper;
    private final BaseProductMapper productMapper;
    /** v1.1.119: BOM 明细保存时回填原料主单位 (治 unit_id/unit_name 全 NULL) */
    private final BaseProductUnitMapper unitMapper;
    private final PermissionService permService;
    private final BillNoGenerator billNoGenerator;
    private final OperLogPublisher operLogPublisher;


    /**
     * 统计一个 BOM(配方)被多少个成品引用. 用于列表 "N 个成品用这个配方" 列.
     * @param bomId 配方 ID
     * @return 引用该配方的成品数量(不含已删除)
     */
    public long countProductsByBomId(Long bomId) {
        if (bomId == null) return 0;
        Long cnt = productMapper.selectCount(
            new LambdaQueryWrapper<BaseProduct>()
                .eq(BaseProduct::getBomId, bomId)
                .eq(BaseProduct::getDeleted, 0));
        return cnt != null ? cnt : 0;
    }

    /**
     * 批量统计多个 BOM 的成品引用数, 返回 bomId -> count.
     */
    public java.util.Map<Long, Long> countProductsByBomIds(java.util.List<Long> bomIds) {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        if (bomIds == null || bomIds.isEmpty()) return out;
        List<BaseProduct> rows = productMapper.selectList(
            new LambdaQueryWrapper<BaseProduct>()
                .in(BaseProduct::getBomId, bomIds)
                .eq(BaseProduct::getDeleted, 0)
                .select(BaseProduct::getBomId));
        for (BaseProduct p : rows) {
            if (p.getBomId() != null) {
                out.merge(p.getBomId(), 1L, Long::sum);
            }
        }
        return out;
    }

    public IPage<PrdBom> page(Integer pageNum, Integer pageSize, String keyword) {
        permService.requirePerm("production:bom:list");
        Page<PrdBom> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PrdBom> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            w.like(PrdBom::getBomCode, keyword).or().like(PrdBom::getBomName, keyword);
        }
        w.orderByDesc(PrdBom::getId);
        return bomMapper.selectPage(p, w);
    }

    public PrdBom detail(Long id) {
        PrdBom bom = bomMapper.selectById(id);
        if (bom != null) bom.setDetails(detailMapper.selectByBomId(id));
        return bom;
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="BOM清单", businessType="ADD", saveParam=true)
    public void add(PrdBom bom) {
        permService.requirePerm("production:bom:add");
        if (bom.getStatus() == null) bom.setStatus(1);
        if (bom.getIsDefault() == null) bom.setIsDefault(0);
        if (bom.getLossRate() == null) bom.setLossRate(java.math.BigDecimal.ZERO);
        if (bom.getBomCode() == null || bom.getBomCode().isBlank()) bom.setBomCode(billNoGenerator.generate("BOM"));
        bomMapper.insert(bom);
        saveDetails(bom.getId(), bom.getDetails());
    }

    @Transactional(rollbackFor = Exception.class)
    @OperLog(module="BOM清单", businessType="EDIT", saveParam=true)
    public void update(PrdBom bom) {
        permService.requirePerm("production:bom:edit");
        bomMapper.updateById(bom);
        detailMapper.delete(new LambdaQueryWrapper<PrdBomDetail>().eq(PrdBomDetail::getBomId, bom.getId()));
        saveDetails(bom.getId(), bom.getDetails());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("production:bom:delete");
        PrdBom bom = bomMapper.selectById(id);
        if (bom == null) throw new com.industrial.erp.exception.BizException("BOM不存在或已删除");
        List<PrdBomDetail> details = detailMapper.selectByBomId(id);
        // 软删除主
        bomMapper.update(null, new LambdaUpdateWrapper<PrdBom>()
                .eq(PrdBom::getId, id).set(PrdBom::getDeleted, 1));
        // 软删除子
        if (details != null && !details.isEmpty()) {
            detailMapper.update(null, new LambdaUpdateWrapper<PrdBomDetail>()
                    .eq(PrdBomDetail::getBomId, id).set(PrdBomDetail::getDeleted, 1));
        }
        operLogPublisher.publishDeleteSnapshot("BOM清单", String.valueOf(id), bom, details);
    }

    private void saveDetails(Long bomId, List<PrdBomDetail> details) {
        if (details == null) return;
        int line = 0;
        for (PrdBomDetail d : details) {
            d.setId(null);
            d.setBomId(bomId);
            d.setLineNo(++line);
            // v1.1.119 单位兜底: BOM 明细 11/11 行 unit_id/unit_name 是 NULL ——
            //   Bom.vue 的 addDetail()/onSubmit() 只同步 productCode/productName/spec, 从不写单位.
            //   后果: 领料明细 unitId 也全 NULL → StockService.convertToMain() 的
            //   `if (unitId == null) return qty` 静默跳过单位折算, 扣错库存.
            //   这里从原料主单位回填 (复用现成的 BaseProductUnitMapper.selectMainUnit).
            if ((d.getUnitId() == null || d.getUnitName() == null) && d.getProductId() != null) {
                try {
                    BaseProductUnit main = unitMapper.selectMainUnit(d.getProductId());
                    if (main != null) {
                        d.setUnitId(main.getUnitId());
                        d.setUnitName(main.getUnitName());
                    }
                } catch (Exception e) {
                    // 主单位查不到不该让整单保存失败 — 留空由 save 后的人工核对兜底
                    org.slf4j.LoggerFactory.getLogger(PrdBomService.class)
                            .warn("[PrdBom.saveDetails] 原料 {} 主单位回填失败 (留空): {}", d.getProductId(), e.getMessage());
                }
            }
            detailMapper.insert(d);
        }
    }
}
