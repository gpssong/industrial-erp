package com.industrial.erp.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import com.industrial.erp.modules.system.mapper.SysFeiePrintTemplateMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 飞鹅云打印模板 Service
 */
@Service
public class SysFeiePrintTemplateService {

    private final SysFeiePrintTemplateMapper mapper;

    public SysFeiePrintTemplateService(SysFeiePrintTemplateMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查找默认模板 (供 FeiePrintService 加载用户自定义内容)
     */
    public SysFeiePrintTemplate findDefault(String bizType, Long printerConfigId) {
        return mapper.selectDefault(bizType, printerConfigId);
    }

    public IPage<SysFeiePrintTemplate> page(int pageNum, int pageSize, String bizType, Long printerConfigId) {
        Page<SysFeiePrintTemplate> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysFeiePrintTemplate> w = new LambdaQueryWrapper<>();
        if (bizType != null && !bizType.isEmpty()) {
            w.eq(SysFeiePrintTemplate::getBizType, bizType);
        }
        if (printerConfigId != null) {
            w.eq(SysFeiePrintTemplate::getPrinterConfigId, printerConfigId);
        }
        w.eq(SysFeiePrintTemplate::getDeleted, 0);
        w.orderByDesc(SysFeiePrintTemplate::getUpdateTime);
        return mapper.selectPage(page, w);
    }

    public SysFeiePrintTemplate get(Long id) {
        SysFeiePrintTemplate t = mapper.selectById(id);
        if (t == null) throw BizException.of("模板不存在: id=" + id);
        return t;
    }

    public void save(SysFeiePrintTemplate t) {
        // 如果设默认, 把同 (bizType, printerConfigId) 的其他默认取消
        if (t.getIsDefault() != null && t.getIsDefault() == 1) {
            clearDefault(t.getBizType(), t.getPrinterConfigId(), t.getId());
        }
        mapper.insert(t);
    }

    public void update(SysFeiePrintTemplate t) {
        if (t.getIsDefault() != null && t.getIsDefault() == 1) {
            clearDefault(t.getBizType(), t.getPrinterConfigId(), t.getId());
        }
        // 避免 updateById 更新 is_default 引发 UK 冲突 (clearDefault 已处理)
        t.setIsDefault(null);
        mapper.updateById(t);
    }

    public void delete(Long id) {
        SysFeiePrintTemplate existing = mapper.selectById(id);
        if (existing == null) throw BizException.of("模板不存在: id=" + id);
        existing.setDeleted(1);
        mapper.updateById(existing);
    }

    /**
     * 把同 (bizType, printerConfigId) 的其他模板的 is_default 改为 0
     */
    private void clearDefault(String bizType, Long printerConfigId, Long excludeId) {
        List<SysFeiePrintTemplate> others = mapper.selectList(
            new LambdaQueryWrapper<SysFeiePrintTemplate>()
                .eq(SysFeiePrintTemplate::getBizType, bizType)
                .eq(SysFeiePrintTemplate::getPrinterConfigId, printerConfigId)
                .eq(SysFeiePrintTemplate::getIsDefault, 1)
                .ne(excludeId != null, SysFeiePrintTemplate::getId, excludeId)
                .eq(SysFeiePrintTemplate::getDeleted, 0)
        );
        for (SysFeiePrintTemplate o : others) {
            o.setIsDefault(0);
            mapper.updateById(o);
        }
    }
}