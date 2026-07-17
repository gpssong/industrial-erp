package com.industrial.erp.modules.production.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
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

    public PrdBomService(PrdBomMapper bomMapper, PrdBomDetailMapper detailMapper, BaseProductMapper productMapper, PermissionService permService, BillNoGenerator billNoGenerator, OperLogPublisher operLogPublisher) {
        this.bomMapper = bomMapper;
        this.detailMapper = detailMapper;
        this.productMapper = productMapper;
        this.permService = permService;
        this.billNoGenerator = billNoGenerator;
        this.operLogPublisher = operLogPublisher;
    }

    private final PrdBomMapper bomMapper;
    private final PrdBomDetailMapper detailMapper;
    private final BaseProductMapper productMapper;
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
            detailMapper.insert(d);
        }
    }
}
