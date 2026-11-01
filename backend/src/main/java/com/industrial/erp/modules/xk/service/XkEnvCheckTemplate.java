package com.industrial.erp.modules.xk.service;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.1.151 — 生产环境检查项模板 (GB 31603-2015《食品接触材料及制品生产通用卫生规范》).
 *
 * <p>唯一口径出处: {@code docs/30_生产环境检查项GB31603对照.md}。改检查项先改文档再改这里。
 *
 * <p>10 项通用 + 1 项工序专项 (吹膜 / 制袋各一), 供 App 开工确认与 PC XK 生产环境检查共用,
 * 避免两端各自维护清单导致口径漂移。每项带条款号, XK 现场核查时可直接对表。
 */
public final class XkEnvCheckTemplate {

    public static final String STAGE_BLOW = "BLOW";
    public static final String STAGE_BAG = "BAG";

    /** 检查项归属的 xk_env_check.env_type */
    public static final String TYPE_CLEANLINESS = "CLEANLINESS";
    public static final String TYPE_TEMP_HUMIDITY = "TEMP_HUMIDITY";

    /** 单项检查项: 代码 / 名称 / 判定要点 / 依据条款 / 落库类型 */
    public record Item(String code, String name, String requirement, String basis, String envType) {}

    private static final List<Item> COMMON = List.of(
            new Item("ENV_01", "车间地面、墙面、顶棚平整清洁",
                    "无积水、无霉斑、无脱落物, 表面便于清洗", "GB 31603-2015 4.3", TYPE_CLEANLINESS),
            new Item("ENV_02", "更衣室与换鞋设施可用",
                    "工作服与个人服装分开存放, 换鞋 (鞋套) 到位", "GB 31603-2015 5.1.6", TYPE_CLEANLINESS),
            new Item("ENV_03", "洗手、消毒、除尘设施可用",
                    "设施完好, 消毒液在有效期内, 专人管理并保持清洁", "GB 31603-2015 5.1.6、5.1.8", TYPE_CLEANLINESS),
            new Item("ENV_04", "生产设备及接触物料部位清洁",
                    "无油污、无异物、无上一批生产残留", "GB 31603-2015 5.2.3", TYPE_CLEANLINESS),
            new Item("ENV_05", "原辅料、半成品离地离墙分区存放",
                    "外包装完好, 待检/合格分区并有明显标识", "GB 31603-2015 7.6、8.3.1", TYPE_CLEANLINESS),
            new Item("ENV_06", "车间无虫害迹象",
                    "防蝇、防虫、防鼠设施完好", "GB 31603-2015 3.4、5.1.4", TYPE_CLEANLINESS),
            new Item("ENV_07", "人员穿戴与个人卫生符合要求",
                    "工作衣、帽、鞋齐全, 不佩戴饰物, 无与生产无关物品", "GB 31603-2015 6.6", TYPE_CLEANLINESS),
            new Item("ENV_08", "车间照明充足",
                    "采光或人工照明满足操作要求, 灯具防护完好", "GB 31603-2015 5.1.10", TYPE_CLEANLINESS),
            new Item("ENV_09", "车间温湿度符合工艺要求",
                    "温湿度在工艺规定范围内, 监控设施正常", "GB 31603-2015 5.1.11", TYPE_TEMP_HUMIDITY),
            new Item("ENV_10", "通道畅通、废弃物管理到位",
                    "人流物流通道无杂物堆积, 废弃物容器加盖并及时清理", "GB 31603-2015 4.5、5.1.5", TYPE_CLEANLINESS)
    );

    private static final Item BLOW_SPECIAL = new Item("ENV_11", "吹膜风环与冷却风/压缩空气洁净",
            "风环、过滤网、模头无焦料, 冷却风/压缩空气无油污、水珠喷出", "GB 31603-2015 5.2.3", TYPE_CLEANLINESS);

    private static final Item BAG_SPECIAL = new Item("ENV_11", "热封刀、成型器、切刀清洁",
            "无油污、无上一批残留, 设备润滑剂不污染产品", "GB 31603-2015 5.2.3、7.8", TYPE_CLEANLINESS);

    private XkEnvCheckTemplate() {}

    /** 工序对应的完整检查清单 (10 项通用 + 1 项专项); stage 非 BAG 一律按吹膜处理。 */
    public static List<Item> items(String stage) {
        List<Item> list = new ArrayList<>(COMMON);
        list.add(STAGE_BAG.equals(normalizeStage(stage)) ? BAG_SPECIAL : BLOW_SPECIAL);
        return list;
    }

    public static String normalizeStage(String stage) {
        return STAGE_BAG.equalsIgnoreCase(stage == null ? null : stage.trim()) ? STAGE_BAG : STAGE_BLOW;
    }

    /** 未确认或判不合格的检查项; 全部符合时返回空列表。results 以 code 为键, null 值视为未确认。 */
    public static List<Item> problems(String stage, Map<String, Boolean> results) {
        List<Item> bad = new ArrayList<>();
        for (Item item : items(stage)) {
            if (results == null || !Boolean.TRUE.equals(results.get(item.code()))) {
                bad.add(item);
            }
        }
        return bad;
    }

    /** 台账快照单行: "1. 车间地面… — 符合" / "5. 原辅料… — 不符合（外包装破损）"。 */
    public static String formatLine(int no, Item item, boolean pass, String note) {
        String verdict = pass ? "符合"
                : ("不符合" + (StrUtil.isBlank(note) ? "" : "（" + note.trim() + "）"));
        return no + ". " + item.name() + " — " + verdict;
    }

    /** 快照表头: 记录本次检查的车间、工序、生产单与标准出处。 */
    public static String header(String workshop, String stage, String billNo, String productName, int count) {
        String action = STAGE_BAG.equals(normalizeStage(stage)) ? "制袋" : "吹膜";
        String product = StrUtil.isBlank(productName) ? "" : "（" + productName + "）";
        return "GB 31603-2015 生产环境检查（" + workshop + " · " + action + "开工前"
                + (count > 0 ? ", 本记录 " + count + " 项" : "") + "）\n"
                + "生产单 " + StrUtil.nullToEmpty(billNo) + product;
    }
}
