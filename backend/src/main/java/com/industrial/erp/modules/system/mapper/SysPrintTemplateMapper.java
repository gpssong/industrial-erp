package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysPrintTemplateMapper extends BaseMapper<SysPrintTemplate> {
    /**
     * 按 biz_type 选启用模板, 默认模板优先, 否则取最早创建的一个
     */
    List<SysPrintTemplate> selectActiveByBizType(@Param("bizType") String bizType);
}