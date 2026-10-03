package com.industrial.erp.modules.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * v1.1.68 回收站 mapper.
 *
 * <p>被软删除 (deleted=1) 的业务单据统一在此查询/恢复/彻底删除.
 * 全部走**原生 SQL** (XML), 绕过 MyBatis-Plus {@code @TableLogic} 的逻辑删过滤
 * (内置 MP mapper 会自动 WHERE deleted=0, 读不到被删行), 因此这里显式 WHERE deleted=1.
 *
 * <p>表名 / FK 列名由 {@code RecycleType} 白名单枚举在 service 层解析后**作为参数传入**,
 * XML 用 {@code ${}} 拼接 (表名不能参数化); 由于这些值只可能来自枚举的固定集合,
 * 不接收任何前端原值, 无 SQL 注入面.
 */
@Mapper
public interface RecycleBinMapper {

    /** 查被删 head 列表 (WHERE deleted=1), 支持关键词 + 日期范围. headTable/detailTable/detailFk 来自 RecycleType. */
    List<Map<String, Object>> listDeleted(@Param("headTable") String headTable,
                                          @Param("detailTable") String detailTable,
                                          @Param("detailFk") String detailFk,
                                          @Param("hasDetail") boolean hasDetail,
                                          @Param("keyword") String keyword,
                                          @Param("startDate") String startDate,
                                          @Param("endDate") String endDate);

    /** 恢复 head: deleted 1->0. */
    int restoreHead(@Param("headTable") String headTable, @Param("id") Long id);

    /** 恢复 detail (FK 关联 head.id): deleted 1->0. 无明细的 head 调用前由 service 跳过. */
    int restoreDetail(@Param("detailTable") String detailTable,
                      @Param("detailFk") String detailFk, @Param("headId") Long headId);

    /** 彻底删 detail: 物理 DELETE (不可逆). */
    int physicalDeleteDetail(@Param("detailTable") String detailTable,
                             @Param("detailFk") String detailFk, @Param("headId") Long headId);

    /** 彻底删 head: 物理 DELETE (不可逆). */
    int physicalDeleteHead(@Param("headTable") String headTable, @Param("id") Long id);

    /** 取某 head 行的存在性 + 关键字段 (恢复/彻底删前校验). 返回 null=不存在. */
    Map<String, Object> peekHead(@Param("headTable") String headTable, @Param("id") Long id);
}
