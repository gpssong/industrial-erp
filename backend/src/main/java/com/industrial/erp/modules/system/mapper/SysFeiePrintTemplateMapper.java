package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 飞鹅云打印模板 Mapper
 */
@Mapper
public interface SysFeiePrintTemplateMapper extends BaseMapper<SysFeiePrintTemplate> {

    /**
     * 查找指定 (bizType, printerConfigId) 的默认模板
     */
    SysFeiePrintTemplate selectDefault(@Param("bizType") String bizType,
                                       @Param("printerConfigId") Long printerConfigId);
}