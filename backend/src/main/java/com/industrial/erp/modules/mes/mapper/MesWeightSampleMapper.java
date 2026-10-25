package com.industrial.erp.modules.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.mes.entity.MesWeightSample;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * v1.1.119 MES — 米克重采样流水 Mapper.
 *
 * <p>曲线查询和时间窗统计需要原生 SQL (MyBatis-Plus QueryWrapper 表达不了时间分桶聚合),
 * 实现在 {@code resources/mapper/production/MesWeightSampleMapper.xml}。
 *
 * <p>⚠️ XML 里所有手写 SQL 必须自带 {@code AND deleted = 0} ——
 * {@code @TableLogic} 只对 MyBatis-Plus 自动生成的 SQL 生效。
 */
@Mapper
public interface MesWeightSampleMapper extends BaseMapper<MesWeightSample> {

    /**
     * 曲线数据: 按时间倒序取最近 limit 条, 再在 Service 里转正序给 ECharts.
     *
     * @param deviceId  采集器 id (必填)
     * @param channelNo 通道号 (null = 全部通道)
     * @param from      起始时间 (null = 不限)
     * @param to        结束时间 (null = 不限)
     * @param limit     条数上限 (前端传, 防止一次拉爆)
     */
    List<Map<String, Object>> selectCurve(@Param("deviceId") Long deviceId,
                                          @Param("channelNo") Integer channelNo,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("limit") int limit);

    /**
     * 统计: 时间窗内每个通道的均值/最大/最小/超标次数/采样数.
     */
    List<Map<String, Object>> selectStats(@Param("deviceId") Long deviceId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * 清理超过 keepDays 天的历史采样 (时序表会无限增长, 需定期归档).
     */
    int deleteOlderThan(@Param("keepDays") int keepDays);
}