package com.industrial.erp.modules.base.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BaseCustomerService {

    public BaseCustomerService(BaseCustomerMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }
    private final BaseCustomerMapper mapper;
    private final PermissionService permService;

    public IPage<BaseCustomer> page(Integer pageNum, Integer pageSize, String keyword) {
        permService.requirePerm("base:customer:list");
        Page<BaseCustomer> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BaseCustomer> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) w.and(q -> q.like(BaseCustomer::getCustomerCode, keyword)
                .or().like(BaseCustomer::getCustomerName, keyword).or().like(BaseCustomer::getPhone, keyword));
        w.orderByDesc(BaseCustomer::getId);
        return mapper.selectPage(p, w);
    }

    public BaseCustomer detail(Long id) { return mapper.selectById(id); }

    public void add(BaseCustomer c) {
        permService.requirePerm("base:customer:add");
        if (c.getStatus() == null) c.setStatus(1);
        if (c.getCreditLimit() == null) c.setCreditLimit(BigDecimal.ZERO);
        if (c.getCreditUsed() == null) c.setCreditUsed(BigDecimal.ZERO);
        if (c.getTaxRate() == null) c.setTaxRate(new BigDecimal("13.00"));
        mapper.insert(c);
    }

    public void update(BaseCustomer c) {
        permService.requirePerm("base:customer:edit");
        mapper.updateById(c);
    }

    public void delete(Long id) {
        permService.requirePerm("base:customer:delete");
        mapper.deleteById(id);
    }

    /** 校验信用额度 */
    public void validateCredit(Long customerId, BigDecimal thisAmount) {
        if (customerId == null) return;
        BaseCustomer c = mapper.selectById(customerId);
        if (c == null) throw BizException.of("客户不存在");
        if (c.getCreditLimit() != null && c.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal will = (c.getCreditUsed() == null ? BigDecimal.ZERO : c.getCreditUsed()).add(thisAmount);
            if (will.compareTo(c.getCreditLimit()) > 0) {
                throw BizException.of("客户信用额度不足, 信用额度=" + c.getCreditLimit() + ", 已用=" + c.getCreditUsed());
            }
        }
    }
}
