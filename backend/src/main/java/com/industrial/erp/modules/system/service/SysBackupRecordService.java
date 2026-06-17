package com.industrial.erp.modules.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.industrial.erp.modules.system.entity.SysBackupRecord;
import com.industrial.erp.modules.system.mapper.SysBackupRecordMapper;
import org.springframework.stereotype.Service;

@Service
public class SysBackupRecordService extends ServiceImpl<SysBackupRecordMapper, SysBackupRecord> implements IService<SysBackupRecord> {
}
