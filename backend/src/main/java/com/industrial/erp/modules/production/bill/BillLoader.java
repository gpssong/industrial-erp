package com.industrial.erp.modules.production.bill;

import java.util.Map;

/**
 * 飞鹅云打印 — 单据数据加载器
 *
 * <p>每个单据类型 (PRD_ORDER / SAL_DELIVERY / ...) 提供一个实现,
 * 把单据 ID 解析为 Freemarker 模型:
 * <pre>
 *   {
 *     "bill": 单据主表实体 (Map/Entity),
 *     "details": 明细行列表,
 *     "extra":  其他附加数据
 *   }
 * </pre>
 *
 * <p>由 {@link com.industrial.erp.modules.production.service.FeiePrintService} 调度.
 */
public interface BillLoader {

    /**
     * @return 单据类型编码 (与 BIZ_TYPES 一致, 如 PRD_ORDER/SAL_DELIVERY/...)
     */
    String bizType();

    /**
     * @return 单据 Freemarker 模板路径 (相对 classpath:templates/), 如 print/sal_delivery_feie.ftl
     */
    String templatePath();

    /**
     * 加载单据完整数据 (含明细)
     *
     * @param billId 单据 ID
     * @return Freemarker 渲染模型; 抛出 BizException 表示单据不存在或状态异常
     */
    Map<String, Object> load(Long billId);

    /**
     * 单据号 (冗余写入日志便于查询); 返回 null 时仅写 billId
     */
    default String billNo(Long billId) {
        return null;
    }
}