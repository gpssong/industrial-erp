package com.industrial.erp.modules.base.label.context;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.LabelBizType;

import java.util.List;
import java.util.Map;

/**
 * v1.1.167 按单据取标签字段 (docs/32 §4)。分层先例 = production/bill/BillLoader。
 *
 * <p>返回一项 = 打一张标签：原料垛标/箱标按明细行展开，成品卷标单据本身就是一行。
 * 主数据（生产者名称/XK 证号…）不在这里放，由 LabelContextService 统一并进来。
 */
public interface LabelContextLoader {

    LabelBizType bizType();

    /**
     * @param bizId   单据 id
     * @param lineIds 明细行 id 子集；null 或空 = 全部明细行
     */
    List<Map<String, Object>> load(Long bizId, List<Long> lineIds);

    /**
     * 编辑器的示例数据 (docs/35 §3.4)：不碰业务表，让画布在没有真单时也能渲染出真实版式。
     *
     * <p>每次调用必须返回新 map —— 调用方（LabelContextService）会往里并主数据键，复用静态模板会互相污染。
     */
    default List<Map<String, Object>> sample() {
        throw BizException.of(400, "该标签类型尚未配置示例数据");
    }
}
