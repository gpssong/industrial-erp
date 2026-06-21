package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.mapper.SysPrintTemplateMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(rollbackFor = Exception.class)
    public void add(SysPrintTemplate t) {
        permService.requirePerm("system:print:add");
        if (t.getIsDefault() == null) t.setIsDefault(0);
        if (t.getStatus() == null) t.setStatus(1);
        if (StrUtil.isBlank(t.getTemplateCode())) {
            t.setTemplateCode("TPL-" + System.currentTimeMillis());
        }
        // 同类型仅允许一个默认模板, 设置为默认时取消其他
        if (Integer.valueOf(1).equals(t.getIsDefault())) {
            clearOtherDefaults(t.getTemplateType(), null);
        }
        templateMapper.insert(t);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SysPrintTemplate t) {
        permService.requirePerm("system:print:edit");
        // 同类型仅允许一个默认模板, 设置为默认时取消其他 (排除自身)
        if (Integer.valueOf(1).equals(t.getIsDefault())) {
            clearOtherDefaults(t.getTemplateType(), t.getId());
        }
        templateMapper.updateById(t);
    }

    public void delete(Long id) {
        permService.requirePerm("system:print:delete");
        templateMapper.deleteById(id);
    }

    /**
     * 把同 templateType 的其他模板的 isDefault 置为 0 (单类型唯一默认).
     *
     * @param templateType 单据类型 (SAL_DELIVERY / PUR_RECEIPT / ...)
     * @param excludeId    排除自身 (编辑时使用), 新增传 null
     */
    private void clearOtherDefaults(String templateType, Long excludeId) {
        if (StrUtil.isBlank(templateType)) return;
        LambdaUpdateWrapper<SysPrintTemplate> w = new LambdaUpdateWrapper<>();
        w.eq(SysPrintTemplate::getTemplateType, templateType)
         .eq(SysPrintTemplate::getIsDefault, 1)
         .set(SysPrintTemplate::getIsDefault, 0);
        if (excludeId != null) w.ne(SysPrintTemplate::getId, excludeId);
        templateMapper.update(null, w);
    }
}
