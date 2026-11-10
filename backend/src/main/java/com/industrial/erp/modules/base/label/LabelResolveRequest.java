package com.industrial.erp.modules.base.label;

import java.util.List;

/**
 * POST /base/label/resolve 的请求体 (docs/35 §3.2)。
 *
 * <p>**收草稿、不收 templateId**：画布上正在拖的那份 elements 还没落库，按 id 取只会取到旧的那份。
 * header 省略 = 空 Header（纸头全走保存侧同一套默认）；sample 省略 = true（编辑器进来先看示例，
 * 不先查业务表）；sample=false 必须带 bizId —— 缺了就明确 400，不许偷偷退回示例值。
 */
public record LabelResolveRequest(String bizType, String elements, Header header,
                                  Boolean sample, Long bizId, List<Long> lineIds) {

    /** 草稿的纸头；一个都不给就是「全默认」，不是「库里那张模板的纸头」（草稿可能还没有库里的行）。 */
    public record Header(Integer widthMm, Integer heightMm, Integer gapMm,
                         Integer dpi, String direction, Integer copies) {}

    /** header=null 与 header 各字段全 null 等价，省掉每个消费方再判一遍 null。 */
    public static final Header EMPTY_HEADER = new Header(null, null, null, null, null, null);
}
