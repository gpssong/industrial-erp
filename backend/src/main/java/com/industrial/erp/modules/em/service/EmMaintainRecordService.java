package com.industrial.erp.modules.em.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.em.entity.EmMaintainRecord;
import com.industrial.erp.modules.em.mapper.EmMaintainRecordMapper;
import com.industrial.erp.security.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v1.1.96 EM — 维保记录服务.
 *
 * <p>记录由工单完成时自动写入, 不允许直接新增/修改/删除 (主业务不动).
 * 仅提供 list / detail / check(审核) 三类只读+审核操作.
 */
@Service
public class EmMaintainRecordService {
    private static final Logger log = LoggerFactory.getLogger(EmMaintainRecordService.class);

    private final EmMaintainRecordMapper mapper;
    private final PermissionService permService;

    public EmMaintainRecordService(EmMaintainRecordMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }

    @Transactional(readOnly = true)
    public IPage<EmMaintainRecord> page(String equipmentCode, Integer pageNum, Integer pageSize) {
        permService.requirePerm("equipment:maintain-record:list");
        Page<EmMaintainRecord> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EmMaintainRecord> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(equipmentCode)) {
            w.like(EmMaintainRecord::getEquipmentCode, equipmentCode);
        }
        w.orderByDesc(EmMaintainRecord::getCompleteTime);
        return mapper.selectPage(p, w);
    }

    @Transactional(readOnly = true)
    public EmMaintainRecord findById(Long id) {
        permService.requirePerm("equipment:maintain-record:list");
        EmMaintainRecord r = mapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        return r;
    }

    /**
     * 审核 (一审 = 确认归档). 重复审核幂等.
     */
    @Transactional(rollbackFor = Exception.class)
    public EmMaintainRecord check(Long id, String reviewer) {
        permService.requirePerm("equipment:maintain-record:check");
        EmMaintainRecord origin = mapper.selectById(id);
        if (origin == null) throw BizException.of("记录不存在");
        if (StrUtil.isBlank(origin.getReviewer())) {
            origin.setReviewer(reviewer);
            origin.setReviewTime(java.time.LocalDateTime.now());
            mapper.updateById(origin);
            log.info("[EM] 审核维保记录 recordNo={} reviewer={}", origin.getRecordNo(), reviewer);
        }
        return origin;
    }
}
