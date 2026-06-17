package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvTransferDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvTransferDetailMapper extends BaseMapper<InvTransferDetail> {
    List<InvTransferDetail> selectByTransferId(@Param("id") Long id);
}
