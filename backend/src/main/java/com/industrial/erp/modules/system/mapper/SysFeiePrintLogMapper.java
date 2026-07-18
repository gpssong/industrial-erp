package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysFeiePrintLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 飞鹅云打印日志 Mapper
 */
@Mapper
public interface SysFeiePrintLogMapper extends BaseMapper<SysFeiePrintLog> {

    /**
     * 分页查询 (支持 bizType / billId / status / 时间范围)
     */
    IPage<SysFeiePrintLog> selectLogPage(Page<SysFeiePrintLog> page,
                                          @Param("bizType") String bizType,
                                          @Param("billId") Long billId,
                                          @Param("status") Integer status,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);
}