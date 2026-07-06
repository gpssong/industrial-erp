package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysDept;
import com.industrial.erp.modules.system.mapper.SysDeptMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysDeptService {

    public SysDeptService(SysDeptMapper deptMapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.deptMapper = deptMapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }
    private final SysDeptMapper deptMapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public List<SysDept> listTree() {
        permService.requirePerm("system:dept:list");
        List<SysDept> all = deptMapper.selectList(null);
        return all.stream().filter(d -> d.getParentId() == 0).collect(Collectors.toList());
    }

    public List<SysDept> list() {
        return deptMapper.selectList(null);
    }

    public void add(SysDept d) { permService.requirePerm("system:dept:add"); if (d.getStatus()==null) d.setStatus(1); deptMapper.insert(d); }
    public void update(SysDept d) { permService.requirePerm("system:dept:edit"); deptMapper.updateById(d); }
    public void delete(Long id) {
        permService.requirePerm("system:dept:delete");
        SysDept d = deptMapper.selectById(id);
        if (d == null) throw BizException.of("部门不存在或已删除");
        deptMapper.update(null, new LambdaUpdateWrapper<SysDept>().eq(SysDept::getId, id).set(SysDept::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("部门管理", String.valueOf(id), d, null);
    }
}
