package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysConfig;
import com.industrial.erp.modules.system.mapper.SysConfigMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

@Service
public class SysConfigService {

    public SysConfigService(SysConfigMapper configMapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.configMapper = configMapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final SysConfigMapper configMapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

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
        SysConfig c = configMapper.selectById(id);
        if (c == null) throw BizException.of("系统参数不存在或已删除");
        configMapper.update(null, new LambdaUpdateWrapper<SysConfig>()
                .eq(SysConfig::getId, id).set(SysConfig::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("系统参数", String.valueOf(id), c, null);
    }

    public String getByKey(String key) {
        LambdaQueryWrapper<SysConfig> w = new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key);
        SysConfig c = configMapper.selectOne(w);
        return c != null ? c.getConfigValue() : null;
    }

    public void updateValue(String key, String value) {
        LambdaQueryWrapper<SysConfig> w = new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key);
        SysConfig c = configMapper.selectOne(w);
        if (c != null) {
            c.setConfigValue(value);
            configMapper.updateById(c);
        } else {
            SysConfig n = new SysConfig();
            n.setConfigKey(key);
            n.setConfigValue(value);
            n.setConfigName(key);
            n.setConfigType(1);
            configMapper.insert(n);
        }
    }
}
