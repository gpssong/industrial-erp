package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.modules.system.entity.SysDept;
import com.industrial.erp.modules.system.mapper.SysDeptMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysDeptService {

    public SysDeptService(SysDeptMapper deptMapper, PermissionService permService) {
        this.deptMapper = deptMapper;
        this.permService = permService;
    }
    private final SysDeptMapper deptMapper;
    private final PermissionService permService;

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
    public void delete(Long id) { permService.requirePerm("system:dept:delete"); deptMapper.deleteById(id); }
}
