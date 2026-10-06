package com.industrial.erp.modules.base.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseTenant;
import com.industrial.erp.modules.base.mapper.BaseTenantMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** v1.1.79 工作流: 租客主数据服务 */
@Service
public class BaseTenantService {

    public BaseTenantService(BaseTenantMapper mapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.mapper = mapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final BaseTenantMapper mapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<BaseTenant> page(Integer pageNum, Integer pageSize, String keyword) {
        permService.requirePerm("work:tenant:list");
        Page<BaseTenant> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BaseTenant> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(BaseTenant::getTenantName, keyword)
                    .or().like(BaseTenant::getContactName, keyword)
                    .or().like(BaseTenant::getContactPhone, keyword)
                    .or().like(BaseTenant::getArea, keyword));
        }
        w.orderByDesc(BaseTenant::getId);
        return mapper.selectPage(p, w);
    }

    public List<BaseTenant> list() {
        return mapper.selectList(new LambdaQueryWrapper<BaseTenant>()
                .eq(BaseTenant::getStatus, 1).orderByAsc(BaseTenant::getTenantName));
    }

    public BaseTenant detail(Long id) { return mapper.selectById(id); }

    @OperLog(module = "租客管理", businessType = "ADD", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void add(BaseTenant t) {
        permService.requirePerm("work:tenant:add");
        if (StrUtil.isBlank(t.getTenantName())) throw BizException.of("租客名称不能为空");
        if (t.getStatus() == null) t.setStatus(1);
        mapper.insert(t);
    }

    @OperLog(module = "租客管理", businessType = "EDIT", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void update(BaseTenant t) {
        permService.requirePerm("work:tenant:edit");
        mapper.updateById(t);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("work:tenant:delete");
        BaseTenant t = mapper.selectById(id);
        if (t == null) throw BizException.of("租客不存在或已删除");
        mapper.update(null, new LambdaUpdateWrapper<BaseTenant>()
                .eq(BaseTenant::getId, id).set(BaseTenant::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("租客管理", String.valueOf(id), t, null);
    }
}
