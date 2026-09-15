package com.industrial.erp.common;

import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 给分页列表注入「操作员姓名」工具类。
 * <p>
 * 背景: 项目里所有单据表 (sal_order / sal_delivery / pur_receipt / inv_check / fin_arap 等)
 * 都有 {@code create_by BIGINT} 字段 (操作人 user_id),但前端 el-table 从未展示成中文姓名,
 * 业务员/仓管/财务需要看「谁开的单/谁审的核」时只能去 sys_user 表反查,体验差.
 * <p>
 * 模式: 拿到 {@code IPage<T>} records 后,collect 所有非空 {@code getCreateBy()} →
 * {@code userMapper.selectBatchIds} 一次查 → 注入 {@code setCreateByName(realName)}.
 * 历史脏数据 (createBy IS NULL) / 用户被删 → 保持 null,前端显示 {@code -}.
 *
 * <pre>{@code
 * // Entity 必备字段 (例 SalOrder):
 * @TableField(exist = false)
 * private String createByName;
 * public String getCreateByName() { return createByName; }
 * public void setCreateByName(String createByName) { this.createByName = createByName; }
 *
 * // Controller 调用:
 * List<SalOrder> rows = result.getRecords();
 * CreateByNameInjector.inject(userMapper, rows, SalOrder::getCreateBy, SalOrder::setCreateByName);
 * }</pre>
 *
 * @param <T> 列表元素类型 (实体类)
 */
public final class CreateByNameInjector<T> {

    private final SysUserMapper userMapper;
    private final Function<T, Long> getter;
    private final BiConsumer<T, String> setter;

    private CreateByNameInjector(SysUserMapper userMapper,
                                  Function<T, Long> getter,
                                  BiConsumer<T, String> setter) {
        this.userMapper = userMapper;
        this.getter = getter;
        this.setter = setter;
    }

    public static <T> CreateByNameInjector<T> of(SysUserMapper userMapper,
                                                 Function<T, Long> getter,
                                                 BiConsumer<T, String> setter) {
        return new CreateByNameInjector<>(userMapper, getter, setter);
    }

    /**
     * 一步式注入。records 为 null 或空时直接返回,不做任何查询。
     */
    public void inject(Collection<T> records) {
        if (records == null || records.isEmpty()) return;
        Set<Long> userIds = new HashSet<>();
        for (T row : records) {
            Long uid = getter.apply(row);
            if (uid != null) userIds.add(uid);
        }
        if (userIds.isEmpty()) return;
        Map<Long, String> nameMap = new HashMap<>(userIds.size() * 2);
        for (SysUser u : userMapper.selectBatchIds(userIds)) {
            if (u != null && u.getId() != null) {
                nameMap.put(u.getId(), u.getRealName());
            }
        }
        for (T row : records) {
            Long uid = getter.apply(row);
            if (uid != null) {
                setter.accept(row, nameMap.get(uid));
            }
        }
    }

    /**
     * 静态便捷方法。{@code records} 为 null 或空时直接返回,不做任何查询。
     */
    public static <T> void inject(SysUserMapper userMapper,
                                  Collection<T> records,
                                  Function<T, Long> getter,
                                  BiConsumer<T, String> setter) {
        if (records == null || records.isEmpty()) return;
        of(userMapper, getter, setter).inject(records);
    }
}