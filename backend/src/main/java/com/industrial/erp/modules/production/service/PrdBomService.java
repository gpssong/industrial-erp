package com.industrial.erp.modules.production.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.production.mapper.PrdBomDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdBomMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PrdBomService {

    public PrdBomService(PrdBomMapper bomMapper, PrdBomDetailMapper detailMapper, PermissionService permService, BillNoGenerator billNoGenerator) {
        this.bomMapper = bomMapper;
        this.detailMapper = detailMapper;
        this.permService = permService;
        this.billNoGenerator = billNoGenerator;
    }

    private final PrdBomMapper bomMapper;
    private final PrdBomDetailMapper detailMapper;
    private final PermissionService permService;
    private final BillNoGenerator billNoGenerator;

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
    public void update(PrdBom bom) {
        permService.requirePerm("production:bom:edit");
        bomMapper.updateById(bom);
        detailMapper.delete(new LambdaQueryWrapper<PrdBomDetail>().eq(PrdBomDetail::getBomId, bom.getId()));
        saveDetails(bom.getId(), bom.getDetails());
    }

    public void delete(Long id) {
        permService.requirePerm("production:bom:delete");
        bomMapper.deleteById(id);
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
