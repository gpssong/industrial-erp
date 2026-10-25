package com.industrial.erp.modules.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.mes.entity.MesWeightDevice;
import org.apache.ibatis.annotations.Mapper;

/**
 * v1.1.119 MES — 米克重采集器 Mapper.
 *
 * <p>只用 BaseMapper 即可 (注册表是纯 CRUD), 无需手写 XML。
 */
@Mapper
public interface MesWeightDeviceMapper extends BaseMapper<MesWeightDevice> {
}