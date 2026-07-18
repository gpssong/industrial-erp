package com.industrial.erp.modules.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysFeiePrintLog;
import com.industrial.erp.modules.system.mapper.SysFeiePrintLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 飞鹅云打印日志 Service
 */
@Service
public class SysFeiePrintLogService {

    private final SysFeiePrintLogMapper logMapper;

    public SysFeiePrintLogService(SysFeiePrintLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    public void save(SysFeiePrintLog log) {
        if (log.getCreateTime() == null) {
            log.setCreateTime(LocalDateTime.now());
        }
        logMapper.insert(log);
    }

    public IPage<SysFeiePrintLog> page(int pageNum, int pageSize,
                                        String bizType, Long billId,
                                        Integer status,
                                        LocalDateTime startTime, LocalDateTime endTime) {
        Page<SysFeiePrintLog> page = new Page<>(pageNum, pageSize);
        return logMapper.selectLogPage(page, bizType, billId, status, startTime, endTime);
    }
}