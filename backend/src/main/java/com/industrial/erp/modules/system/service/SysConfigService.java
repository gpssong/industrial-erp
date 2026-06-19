package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysConfig;
import com.industrial.erp.modules.system.mapper.SysConfigMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

@Service
public class SysConfigService {

    public SysConfigService(SysConfigMapper configMapper, PermissionService permService) {
        this.configMapper = configMapper;
        this.permService = permService;
    }

    private final SysConfigMapper configMapper;
    private final PermissionService permService;

    public IPage<SysConfig> page(Integer pageNum, Integer pageSize, String configName, Integer configType) {
        permService.requirePerm("system:config:list");
        Page<SysConfig> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysConfig> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(configName)) w.like(SysConfig::getConfigName, configName);
        if (configType != null) w.eq(SysConfig::getConfigType, configType);
        w.orderByDesc(SysConfig::getId);
        return configMapper.selectPage(p, w);
    }

    public SysConfig detail(Long id) { return configMapper.selectById(id); }

    public void add(SysConfig c) {
        permService.requirePerm("system:config:add");
        configMapper.insert(c);
    }

    public void update(SysConfig c) {
        permService.requirePerm("system:config:edit");
        configMapper.updateById(c);
    }

    public void delete(Long id) {
        permService.requirePerm("system:config:delete");
        configMapper.deleteById(id);
    }

    public String getByKey(String key) {
        LambdaQueryWrapper<SysConfig> w = new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key);
        SysConfig c = configMapper.selectOne(w);
        return c != null ? c.getConfigValue() : null;
    }
}
