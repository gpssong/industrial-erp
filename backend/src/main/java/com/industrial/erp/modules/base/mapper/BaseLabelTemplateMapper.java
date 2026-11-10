package com.industrial.erp.modules.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BaseLabelTemplateMapper extends BaseMapper<BaseLabelTemplate> {

    /**
     * 加行锁读旧行 (docs/36 Task 6)。
     *
     * <p>快照 + 覆盖 + 裁剪必须是**一串**：两个人同时编辑同一份模板时，各自都按自己读到的旧行
     * 生成历史，就会出现两条 seq 相同、内容却都不是「上一次落库的那版」的历史 —— 历史链断裂，
     * 回滚哪一条都对不上。锁住这一行，后到的编辑必须等前一个提交，读到的才是真的旧行。
     *
     * <p>只锁 {@code deleted=0} 的活行（软删行走 {@code detail()} 的 404，不该被锁住）；
     * 主行锁在事务结束时由 MySQL 释放，服务层不自己解锁 —— 所以整个写路径必须在同一个事务里，
     * {@code BaseLabelTemplateService.update} 上的 {@code @Transactional} 不是可选项。
     */
    @Select("SELECT * FROM base_label_template WHERE id=#{id} AND deleted=0 FOR UPDATE")
    BaseLabelTemplate selectForUpdate(@Param("id") Long id);
}
