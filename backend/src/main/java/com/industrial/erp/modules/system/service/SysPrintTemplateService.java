package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.mapper.SysPrintTemplateMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

@Service
public class SysPrintTemplateService {

    public SysPrintTemplateService(SysPrintTemplateMapper templateMapper, PermissionService permService) {
        this.templateMapper = templateMapper;
        this.permService = permService;
    }

    private final SysPrintTemplateMapper templateMapper;
    private final PermissionService permService;

    public IPage<SysPrintTemplate> page(Integer pageNum, Integer pageSize, String templateName, String templateType) {
        permService.requirePerm("system:print:list");
        Page<SysPrintTemplate> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysPrintTemplate> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(templateName)) w.like(SysPrintTemplate::getTemplateName, templateName);
        if (StrUtil.isNotBlank(templateType)) w.eq(SysPrintTemplate::getTemplateType, templateType);
        w.orderByDesc(SysPrintTemplate::getId);
        return templateMapper.selectPage(p, w);
    }

    public SysPrintTemplate detail(Long id) { return templateMapper.selectById(id); }

    public void add(SysPrintTemplate t) {
        permService.requirePerm("system:print:add");
        if (t.getIsDefault() == null) t.setIsDefault(0);
        if (t.getStatus() == null) t.setStatus(1);
        if (StrUtil.isBlank(t.getTemplateCode())) {
            t.setTemplateCode("TPL-" + System.currentTimeMillis());
        }
        templateMapper.insert(t);
    }

    public void update(SysPrintTemplate t) {
        permService.requirePerm("system:print:edit");
        templateMapper.updateById(t);
    }

    public void delete(Long id) {
        permService.requirePerm("system:print:delete");
        templateMapper.deleteById(id);
    }
}
