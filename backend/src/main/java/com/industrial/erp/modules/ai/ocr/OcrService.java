package com.industrial.erp.modules.ai.ocr;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.config.AiProperties;
import com.industrial.erp.modules.system.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.1.158 AI 网关 — 视觉 OCR (原料标签批号识别).
 *
 * <p>设计 (与 {@code AsrService} 同款独立能力, 不走文本 LLM failover 链):
 * <ul>
 *   <li>两种服务商, 由 {@code ocr.provider} 选: {@code textin} = 合合信息 TextIn 通用文字识别
 *       (逐行文字 + 坐标框, 专用 OCR, 便宜快准) / {@code llm} = OpenAI 兼容多模态
 *       {@code /chat/completions} (content = [{type:text},{type:image_url}]).</li>
 *   <li>key/凭证走 {@link AiProperties} 的 ocr / textin 槽 (env 或 DB AI_INTEGRATION.ocr, 即时生效).</li>
 *   <li>两边都没配 {@link #enabled()} false, 调用快速失败 (抛 {@code BizException}), 不影响 LLM/ASR/其它功能.</li>
 *   <li>照片以磁盘文件读入再送 (TextIn 走原始二进制 body; 视觉模型走 base64 data URL)
 *       —— 外部服务商无法回源内网上传 URL, 故不走远程图片引用.</li>
 *   <li>两条路径共用同一套「逐行挑批号候选」口径 ({@link #photoCandidates}), 前端只认
 *       {@code candidates[0]}, 所以排序质量就是识别质量.</li>
 * </ul>
 */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    /** 单次识别最多处理照片数 (与前端标签照片上限一致). */
    private static final int MAX_PHOTOS = 6;

    /** 单次送模型的最大字节 (base64 前), 防止超大图撑爆请求. */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /**
     * 批号候选: 字母数字串 (允许内部连字符), 长度 4-30.
     * 必须支持 {@code B2426H-261017} —— 2026-11-03 用真实 TextIn 响应验过,
     * 老写法 {@code [A-Za-z0-9]{4,30}} 会把它切成 {@code B2426H} + {@code 261017} 两半.
     */
    private static final Pattern BATCH_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-]{2,29}[A-Za-z0-9]");

    /** TextIn v2 通用文字识别路径 (v1.1.163 实测: 老 robot 端点回 40007 机器人不存在). */
    private static final String TEXTIN_PATH = "/ai/service/v2/recognize";

    /** 服务商标识. */
    static final String PROVIDER_TEXTIN = "textin";
    static final String PROVIDER_LLM = "llm";
    static final String PROVIDER_NONE = "none";

    /** 命中即认定该行是「批号行」的字段名 (先去空白再比, 兼容「批 号」这种排版). */
    private static final String[] BATCH_LABEL_KEYS = {"批号", "批次", "批號", "生产批", "lot", "batch"};

    /**
     * 命中即整行丢弃的字段名: 日期/重量/标准号/许可证/联系方式/品名这些行里的字母数字最容易冒充批号.
     * 行里同时出现「批号/LOT」时本表让路 (见 {@link #batchTokensOf}), 免得把真批号连同字段名一起丢掉.
     */
    private static final String[] NOISE_KEYS = {
            "日期", "时间", "净重", "毛重", "重量", "规格", "标准", "许可证", "执行",
            "电话", "手机", "传真", "地址", "邮编", "网址", "邮箱", "生产厂", "厂商", "保质期", "有效期",
            "品名", "牌号", "型号"};

    /**
     * 通用树脂名: 哪张原料标签上都有, 拿它当锚点等于没做定向 —— 会把别的原料的批号当成答案
     * (v1.1.165 实测: 「扬巴ldpe2426h」用 ldpe 锚到了赛科那张标签的 LLDPE 行).
     */
    private static final Set<String> RESIN_WORDS = Set.of(
            "ldpe", "lldpe", "hdpe", "mldpe", "mdmmdpe", "pe", "pp", "cpp", "mpp", "bopp", "eva", "pvc");

    /** 常用户料的中文简称 → 标签上实际的英文写法 (袋子上印 BASF / SECCO, 不印「扬巴」「赛科」). */
    private static final Map<String, List<String>> SUPPLIER_ALIASES = Map.of(
            "赛科", List.of("secco"),
            "扬巴", List.of("basf"));

    /** 上传根目录 (与 SysUploadController / WebMvcConfig 同口径). */
    private final String uploadDir = (System.getenv("ERP_UPLOAD_PATH") != null
            && !System.getenv("ERP_UPLOAD_PATH").isEmpty())
            ? System.getenv("ERP_UPLOAD_PATH")
            : System.getProperty("user.dir") + File.separator + "upload";

    private final AiProperties props;

    @Autowired(required = false)
    private SysConfigService sysConfigService;

    public OcrService(AiProperties props) {
        this.props = props;
    }

    /**
     * DB 覆盖重读窗口 (ms) — 与 {@code LlmClient.CHAIN_TTL_MS} 同口径.
     *
     * <p>v1.1.165: 状态卡 (/ai/ocr/status) 和 PC/App 的「识别批号」按钮都读 {@link #enabled()},
     * 而它原先只吃启动时的 env。DB 里刚填好 TextIn 凭证时按钮仍然灰着 —— 用户点不到识别、
     * 也就永远触发不了那次 {@code refreshFromDb}, 等于配了等于没配。所以查询侧也要按 TTL 重读。
     */
    private static final long REFRESH_TTL_MS = 30_000L;

    private volatile long lastRefreshAt = 0L;

    /** 读 sys_config.AI_INTEGRATION 刷 OCR 覆盖字段 (DB 即时生效, 无需重建 jar). */
    private void refreshFromDb(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefreshAt < REFRESH_TTL_MS) return;
        lastRefreshAt = now;
        if (sysConfigService == null) return;
        try {
            String json = sysConfigService.getByKey(LlmClient.DB_KEY);
            props.applyIntegrationJson(json);   // null → 清 override, 纯 env
        } catch (Exception e) {
            log.warn("[OCR] 读 sys_config.{} 失败, 回落纯 env: {}", LlmClient.DB_KEY, e.getMessage());
        }
    }

    /** 立即重读 DB 覆盖 (保存对接方式后调用, 让状态卡当场反映新配置). */
    public void refreshNow() {
        refreshFromDb(true);
    }

    /**
     * 实际生效的 OCR 服务商: 显式选的那个配齐了就用它, 否则用另一个已配齐的; 都没有 → {@code none}.
     * 「选了 textin 但凭证还没填」不该把功能整个关灰 —— 有视觉模型 key 时照样能识别.
     */
    public String provider() {
        refreshFromDb(false);
        boolean textin = props.textinConfigured();
        boolean llm = props.llmOcrConfigured();
        String want = props.effectiveOcrProvider();
        if (PROVIDER_TEXTIN.equalsIgnoreCase(want)) return textin ? PROVIDER_TEXTIN : (llm ? PROVIDER_LLM : PROVIDER_NONE);
        if (llm) return PROVIDER_LLM;
        return textin ? PROVIDER_TEXTIN : PROVIDER_NONE;
    }

    /** OCR 是否可用 (任一服务商配齐即启用). */
    public boolean enabled() {
        return !PROVIDER_NONE.equals(provider());
    }

    /** 状态卡展示的「模型」名: TextIn 无模型参数, 回服务商名; 视觉模型回配置的 model. */
    public String model() {
        String p = provider();
        if (PROVIDER_TEXTIN.equals(p)) return "textin-general-ocr";
        if (PROVIDER_LLM.equals(p)) return props.effectiveOcrModel();
        return "";
    }

    /** 识别结果: 模型原始文本 + 正则抽出的批号候选. */
    public static class OcrResult {
        public String rawText = "";
        public final Set<String> candidates = new LinkedHashSet<>();
        /** 本次实际用的服务商/模型名 (仅日志用). */
        String modelUsed = "";
    }

    /**
     * 一行识别结果: 文字 + 在图上的位置 (用于把同一张标签的行聚成一块).
     *
     * <p>TextIn 的 {@code position} 是四个角点共 8 个整数; 拿不到坐标 (视觉大模型 / 行缺字段)
     * 时 {@code w}/{@code h} 记 0, {@link #sameLabelBlock} 见到 0 高就不做空间过滤, 行为退化为
     * v1.1.158 的整图候选.
     */
    static final class TextLine {
        final String text;
        final double cx;
        final double cy;
        final double w;
        final double h;

        private TextLine(String text, double cx, double cy, double w, double h) {
            this.text = text;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }

        static TextLine textOnly(String text) {
            return new TextLine(text, 0, 0, 0, 0);
        }

        /** 从 TextIn 的 line 对象构造; 缺 position / 坐标不全 → 退化为无坐标行. */
        static TextLine of(JSONObject line) {
            String text = line.getStr("text");
            if (text == null || text.trim().isEmpty()) return null;
            JSONArray pos = line.getJSONArray("position");
            if (pos == null || pos.size() < 8) return new TextLine(text, 0, 0, 0, 0);
            double x1 = pos.getDouble(0, 0D), y1 = pos.getDouble(1, 0D);
            double x3 = pos.getDouble(4, 0D), y3 = pos.getDouble(5, 0D);
            double minX = Math.min(x1, x3), maxX = Math.max(x1, x3);
            double minY = Math.min(y1, y3), maxY = Math.max(y1, y3);
            return new TextLine(text, (minX + maxX) / 2D, (minY + maxY) / 2D, maxX - minX, maxY - minY);
        }
    }

    /**
     * v1.1.163 — 带品名定向识别.
     *
     * <p>一单多原料时 (如 赛科0220kj + 扬巴ldpe2426h 同车到货), 照片里有多张标签.
     * 老做法把所有照片的候选合并成一个扁平列表, 前端点哪行都取 {@code candidates[0]}
     * → 两行拿到同一个批号. 传入 {@code productName} 后提示词只找该原料的标签,
     * 逐行调用即可各取各的批号.
     *
     * @param photoUrls   标签照片 URL 列表
     * @param productName 本次要识别的原料名; 空 → 保持 v1.1.158 通用行为 (PC 端未传时不受影响)
     */
    public OcrResult recognizeLabels(List<String> photoUrls, String productName) {
        refreshFromDb(true);
        if (!enabled()) {
            throw new BizException("OCR 未配置: 请在 系统设置→对接方式 选 OCR 服务商并填密钥 "
                    + "(TextIn 的 x-ti-app-id / x-ti-secret-code, 或视觉模型 API Key ERP_AI_OCR_KEY)");
        }
        if (photoUrls == null || photoUrls.isEmpty()) {
            throw new BizException("请至少上传 1 张原料标签照片");
        }

        String provider = provider();
        int n = 0;
        List<String> skipped = new ArrayList<>();
        List<String> readable = new ArrayList<>();
        for (String url : photoUrls) {
            if (n >= MAX_PHOTOS) break;
            byte[] img = loadImageFromUrl(url);
            if (img == null) {
                skipped.add(abbreviate(url, 120));
                continue;
            }
            readable.add(url);
            n++;
        }
        if (n == 0) {
            throw new BizException("标签照片均无法读取, 请重新上传");
        }

        OcrResult r = PROVIDER_TEXTIN.equals(provider)
                ? recognizeWithTextin(readable, productName)
                : recognizeWithVisionLlm(readable, productName);
        if (!skipped.isEmpty()) {
            log.warn("[OCR] {} 张照片读不到已跳过: {}", skipped.size(), skipped);
        }
        log.info("[OCR] 识别完成 (provider={}, model={}, photos={}, candidates={}, product={})",
                provider, r.modelUsed, n, r.candidates.size(), abbreviate(sanitizeHint(productName), 40));
        return r;
    }

    /** v1.1.158 视觉大模型路径: 每张图一次多模态请求, 提示词里做「只要该原料标签」的定向. */
    private OcrResult recognizeWithVisionLlm(List<String> urls, String productName) {
        String hint = sanitizeHint(productName);
        String prompt = buildPrompt(productName);
        OcrResult out = new OcrResult();
        out.modelUsed = props.effectiveOcrModel();
        StringBuilder rawAll = new StringBuilder();
        for (String url : urls) {
            byte[] img = loadImageFromUrl(url);
            if (img == null) continue;                       // 上层已计数并记日志
            String raw = recognizeOne(img, guessMime(url), prompt);
            if (raw != null && !raw.trim().isEmpty()) {
                if (rawAll.length() > 0) rawAll.append("\n");
                rawAll.append(raw.trim());
                out.candidates.addAll(parseCandidates(raw, hint));
            }
        }
        out.rawText = rawAll.toString();
        return out;
    }

    /**
     * v1.1.165 TextIn 路径: 通用 OCR 只把图上文字逐行吐出来, 不懂「只要原料 X 的标签」,
     * 所以定向识别改由服务端用返回的坐标框做 —— 先找含该原料品名/牌号的那行当锚点,
     * 再只在同一张标签的范围内挑批号; 块内没有才退到那张图整图, 一张都锚不到才摊开所有图.
     */
    private OcrResult recognizeWithTextin(List<String> urls, String productName) {
        String hint = sanitizeHint(productName);
        OcrResult out = new OcrResult();
        out.modelUsed = "textin-general-ocr";
        List<List<TextLine>> perPhoto = new ArrayList<>();
        StringBuilder rawAll = new StringBuilder();
        for (String url : urls) {
            byte[] img = loadImageFromUrl(url);
            if (img == null) continue;
            List<TextLine> lines = textinRecognize(img, guessMime(url));
            perPhoto.add(lines);
            for (TextLine l : lines) {
                if (rawAll.length() > 0) rawAll.append("\n");
                rawAll.append(l.text);
            }
        }
        out.rawText = rawAll.toString();
        out.candidates.addAll(photoCandidates(perPhoto, hint));
        return out;
    }

    /**
     * 多张照片 → 批号候选 (三趟, 一趟比一趟宽, 但**越宽越不可信**):
     * ① 只取「锚定到该原料那张标签」块内的候选;
     * ② 锚到了那张标签、可批号行写在块半径之外 (canary 真实照片就是这样: 品名 BASF 在最下面,
     *    批号那行隔了三行远) → 退到**锚到的那几张图**的整图候选;
     * ③ 一张都没锚到 (品名只印在英文商标上 / 标签拍糊) → 才把所有图的候选摊开.
     *
     * <p>单独拆出来是因为 2026-11-03 canary 实测的错出在**跨图合并**这一层: 逐行提取各自都对,
     * 合并后的顺序却把另一张标签的噪声顶到了 {@code candidates[0]}.
     * ② 是关键 —— 少了这一趟, 「锚到标签但块内没批号」会直接跳到 ③, 把**别的原料**的批号
     * 塞进这一行的候选 (扬巴那行拿到赛科的 SK216923C1402249).
     */
    static Set<String> photoCandidates(List<List<TextLine>> perPhoto, String hint) {
        Set<String> out = new LinkedHashSet<>();
        if (perPhoto == null) return out;
        List<String> keys = hintKeys(hint);
        if (!keys.isEmpty()) {
            List<List<TextLine>> anchored = new ArrayList<>();
            for (List<TextLine> lines : perPhoto) {
                TextLine anchor = findAnchorLine(lines, keys);
                if (anchor == null) continue;
                anchored.add(lines);
                collect(out, sameLabelBlock(lines, anchor), keys);
            }
            if (!out.isEmpty()) return out;
            for (List<TextLine> lines : anchored) {
                collect(out, lines, keys);
            }
            if (!out.isEmpty()) return out;
        }
        for (List<TextLine> lines : perPhoto) {
            collect(out, lines, keys);
        }
        return out;
    }

    /** 调 TextIn v2 识别一张图, 返回逐行文字 + 坐标. */
    private List<TextLine> textinRecognize(byte[] image, String contentType) {
        String base = props.effectiveTextinBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String raw;
        int status;
        try (HttpResponse resp = HttpRequest.post(base + TEXTIN_PATH)
                .header("x-ti-app-id", props.effectiveTextinAppId())
                .header("x-ti-secret-code", props.effectiveTextinSecretCode())
                .header("Content-Type", contentType)
                .body(image)
                .timeout(props.effectiveTextinTimeoutMs())
                .execute()) {
            status = resp.getStatus();
            raw = resp.body();
        } catch (Exception e) {
            log.error("[OCR/TextIn] 网络/超时失败: {}", e.getMessage());
            throw new BizException("TextIn 识别网络失败, 请重试: " + e.getMessage());
        }
        if (status < 200 || status >= 300) {
            log.warn("[OCR/TextIn] HTTP {}: {}", status, abbreviate(raw, 300));
            throw new BizException("TextIn HTTP " + status + ", 请检查 app-id / secret-code 是否正确");
        }
        return parseTextinLines(raw);
    }

    /**
     * 解析 TextIn 响应 (纯函数, 单测覆盖).
     * TextIn 出错也回 HTTP 200, 靠 body 里的 {@code code} 判 (如 40306 = 超并发限制), 所以必须看 code 不能只看 HTTP.
     */
    static List<TextLine> parseTextinLines(String json) {
        JSONObject root;
        try {
            root = JSONUtil.parseObj(json);
        } catch (Exception e) {
            throw new BizException("TextIn 响应无法解析, 请重试");
        }
        // 网关 5xx 之类会返回 HTML, hutool 不抛错而是解析成空对象 —— 没有 code 就按「解析不出」报
        if (!root.containsKey("code")) {
            throw new BizException("TextIn 响应无法解析, 请重试");
        }
        int code = root.getInt("code", -1);
        if (code != 200) {
            String msg = root.getStr("message", root.getStr("msg", ""));
            throw new BizException("TextIn 识别失败 " + code + ": " + abbreviate(msg, 80)
                    + (code == 40306 ? " (超出并发限制, 稍等再点一次)" : ""));
        }
        JSONObject result = root.getJSONObject("result");
        List<TextLine> lines = new ArrayList<>();
        if (result == null) return lines;
        JSONArray arr = result.getJSONArray("lines");
        if (arr == null) return lines;
        for (Object o : arr) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject l = (JSONObject) o;
            String text = l.getStr("text");
            if (text == null || text.trim().isEmpty()) continue;
            TextLine tl = TextLine.of(l);
            if (tl != null) lines.add(tl);
        }
        return lines;
    }

    /** 单张图片调视觉模型, 返回模型文本 (content 可能为 string 或 parts 数组, 都归一为文本). */
    private String recognizeOne(byte[] image, String mime, String prompt) {
        String b64 = Base64.getEncoder().encodeToString(image);
        String dataUrl = "data:" + mime + ";base64," + b64;

        JSONObject textPart = JSONUtil.createObj().set("type", "text").set("text", prompt);
        JSONObject imgUrl = JSONUtil.createObj().set("url", dataUrl);
        JSONObject imagePart = JSONUtil.createObj().set("type", "image_url").set("image_url", imgUrl);

        JSONArray content = new JSONArray();
        content.add(textPart);
        content.add(imagePart);

        JSONObject userMsg = JSONUtil.createObj().set("role", "user").set("content", content);
        JSONArray messages = new JSONArray();
        messages.add(userMsg);

        JSONObject body = JSONUtil.createObj()
                .set("model", props.effectiveOcrModel())
                .set("messages", messages)
                .set("max_tokens", 512)
                .set("temperature", 0);

        String url = props.effectiveOcrBaseUrl().endsWith("/")
                ? props.effectiveOcrBaseUrl().substring(0, props.effectiveOcrBaseUrl().length() - 1) + "/chat/completions"
                : props.effectiveOcrBaseUrl() + "/chat/completions";

        String raw;
        int status;
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + props.effectiveOcrKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(props.effectiveOcrTimeoutMs())
                .execute()) {
            status = resp.getStatus();
            raw = resp.body();
        } catch (Exception e) {
            log.error("[OCR] 网络/超时失败: {}", e.getMessage());
            throw new BizException("视觉识别网络失败, 请重试: " + e.getMessage());
        }

        if (status < 200 || status >= 300) {
            log.warn("[OCR] HTTP {}: {}", status, abbreviate(raw, 300));
            throw new BizException("视觉识别 HTTP " + status + ", 请检查模型配置");
        }

        JSONObject out;
        try {
            out = JSONUtil.parseObj(raw);
        } catch (Exception e) {
            log.warn("[OCR] 响应非 JSON: {}", abbreviate(raw, 300));
            return "";
        }
        JSONArray choices = out.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) return "";
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) return "";
        return extractContentText(message.get("content"));
    }

    /** OpenAI 兼容 content 可能是 String 或 [{type:text,text:..}] 数组, 归一为纯文本. */
    private static String extractContentText(Object content) {
        if (content == null) return "";
        if (content instanceof CharSequence) return content.toString();
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (JSONArray) content) {
                if (o instanceof JSONObject) {
                    JSONObject part = (JSONObject) o;
                    String t = part.getStr("text");
                    if (t != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(t);
                    }
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /**
     * 候选排序: ① 带「批号/LOT」字样的行优先, ② 其余行按阅读顺序, ③ 与原料名重合的串沉到最后.
     *
     * <p>③ 是 v1.1.165 canary 实测补的: 赛科那张标签把牌号和批号挤在同一行
     * ({@code LL0220KJ P SK216923C1402249}), 而前端只取 {@code candidates[0]} →
     * 「赛科0220kj」那一行一度填成了牌号 {@code LL0220KJ}. 含原料名片段的候选说的是「这是什么料」,
     * 不是「这批什么号」, 所以排最后 (不删 —— 单行标签时它就是唯一答案).
     */
    private static void collect(Set<String> out, List<TextLine> lines, List<String> hintKeys) {
        List<String> labelled = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        List<String> identity = new ArrayList<>();
        for (TextLine l : lines) {
            boolean batchLine = isBatchLabelLine(l.text);
            for (String tok : batchTokensOf(l)) {
                if (batchLine) labelled.add(tok);
                else if (isIdentityOf(tok, hintKeys)) identity.add(tok);
                else rest.add(tok);
            }
        }
        out.addAll(labelled);
        out.addAll(rest);
        out.addAll(identity);
    }

    /**
     * 一行的批号候选.
     * ① 纯数字只有在「批号」行里才算 (否则日期/电话/邮编全是候选);
     * ② 噪声行整行丢弃, 但行里写了「批号/LOT」时不丢 —— 标签常把品名和批号挤在同一行.
     */
    private static List<String> batchTokensOf(TextLine l) {
        List<String> out = new ArrayList<>();
        String text = l.text;
        boolean labelled = isBatchLabelLine(text);
        if (!labelled && isNoiseLine(text)) return out;
        Matcher m = BATCH_TOKEN.matcher(text);
        while (m.find()) {
            String tok = trimHyphens(m.group());
            if (tok.length() < 4) continue;
            boolean hasDigit = tok.chars().anyMatch(Character::isDigit);
            boolean hasLetter = tok.chars().anyMatch(Character::isLetter);
            if (!hasDigit) continue;              // 纯字母是品牌/厂名词
            if (!hasLetter && !labelled) continue; // 纯数字须落在「批号」行才认
            out.add(tok);
        }
        return out;
    }

    /** 锚点 = 与品名重叠最多的行 (整串命中或牌号/简称片段命中都算, 命中串越长权重越高). */
    private static TextLine findAnchorLine(List<TextLine> lines, List<String> keys) {
        if (keys.isEmpty()) return null;
        TextLine best = null;
        int bestHit = 0;
        for (TextLine l : lines) {
            String norm = normForMatch(l.text);
            int hit = 0;
            for (String k : keys) {
                if (norm.contains(k)) hit += k.length();
            }
            if (hit > bestHit) {
                bestHit = hit;
                best = l;
            }
        }
        return best;
    }

    /**
     * 品名拆成锚点键: 整串 + 断词片段 (+ 厂牌英文名).
     *
     * <p>v1.1.165 canary 实测: 厂里的写法是「赛科0220kj」这种中文简称和牌号**粘连**的, 只按空格/
     * 标点切得到的还是「赛科0220kj」—— 标签上印的是 {@code LL0220KJ}, 于是永远锚不到,
     * 定向识别形同没做. 所以在「汉字↔字母数字」和「字母→数字」两处再断一次:
     * 赛科0220kj → 赛科 + 0220kj; 扬巴ldpe2426h → 扬巴 + (ldpe 丢弃) + 2426h.
     */
    private static List<String> hintKeys(String hint) {
        List<String> keys = new ArrayList<>();
        String whole = normForMatch(hint);
        if (isUsableAnchorKey(whole)) keys.add(whole);
        for (String piece : splitIdentityPieces(hint)) {
            String n = normForMatch(piece);
            if (!isUsableAnchorKey(n) || keys.contains(n)) continue;
            keys.add(n);
            for (String alias : SUPPLIER_ALIASES.getOrDefault(n, List.of())) {
                if (!keys.contains(alias)) keys.add(alias);
            }
        }
        return keys;
    }

    /** 先按分隔符切, 再在每个片段里按 {@link #boundaryBetween} 断词. */
    private static List<String> splitIdentityPieces(String hint) {
        List<String> out = new ArrayList<>();
        if (hint == null) return out;
        for (String token : hint.split("[\\s,，/、()（）\\-]+")) {
            if (token.isEmpty()) continue;
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (cur.length() > 0 && boundaryBetween(token.charAt(i - 1), c)) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(c);
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }

    /** 断词点: 汉字与字母数字交界, 或字母紧跟数字 (牌号尾缀如 2426h 要保持完整). */
    private static boolean boundaryBetween(char prev, char cur) {
        if (isCjk(prev) != isCjk(cur)) return true;
        return Character.isLetter(prev) && Character.isDigit(cur);
    }

    /** 能当锚点的片段: 不是通用树脂名, 且够特别 —— 含汉字的简称 2 字即可, 纯字母数字要 3 位以上. */
    private static boolean isUsableAnchorKey(String norm) {
        if (norm.length() < 2 || RESIN_WORDS.contains(norm)) return false;
        for (int i = 0; i < norm.length(); i++) {
            if (isCjk(norm.charAt(i))) return true;
        }
        return norm.length() >= 3;
    }

    /** 候选串里含原料名的某个片段 → 它说的是「这是什么料」, 不是「这批什么号」. */
    private static boolean isIdentityOf(String token, List<String> hintKeys) {
        String t = normForMatch(token);
        if (t.isEmpty() || hintKeys == null) return false;
        for (String k : hintKeys) {
            if (isUsableAnchorKey(k) && t.contains(k)) return true;
        }
        return false;
    }

    /**
     * 同一张标签的行 = 锚点周围一小块 (垂直 4 行内 + 水平重叠).
     * 两张标签上下叠或左右并排都能分开; 拿不到坐标 (视觉大模型那条路径) 就不过滤.
     */
    private static List<TextLine> sameLabelBlock(List<TextLine> lines, TextLine anchor) {
        List<TextLine> out = new ArrayList<>();
        if (anchor.h <= 0) return lines;
        double maxDy = 4D * anchor.h;
        for (TextLine l : lines) {
            if (l == anchor) {
                out.add(l);
                continue;
            }
            if (l.h <= 0) continue;
            if (Math.abs(l.cy - anchor.cy) <= maxDy
                    && Math.abs(l.cx - anchor.cx) <= Math.max(anchor.w, l.w)) {
                out.add(l);
            }
        }
        return out;
    }

    /** 只留数字、英文字母与汉字并转小写: 全角冒号、空格、连字符这些排版差异不该影响匹配. */
    private static String normForMatch(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray()) {
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || isCjk(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static String trimHyphens(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '-') a++;
        while (b > a && s.charAt(b - 1) == '-') b--;
        return s.substring(a, b);
    }

    static boolean isBatchLabelLine(String text) {
        String t = collapse(text);
        for (String k : BATCH_LABEL_KEYS) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    static boolean isNoiseLine(String text) {
        String t = collapse(text);
        for (String k : NOISE_KEYS) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    /** 去空白 + 转小写, 供关键词包含判断. */
    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 视觉大模型那份是自由文本, 按行切开后走同一套候选口径
     * (所以「批号：B2426H-261017」不再被切成两半, 「生产日期 2026-10-28」也不再混进候选).
     */
    private Set<String> parseCandidates(String text, String hint) {
        if (text == null) return new LinkedHashSet<>();
        List<TextLine> lines = new ArrayList<>();
        for (String s : text.split("\\R")) {
            String t = s.trim();
            if (!t.isEmpty()) lines.add(TextLine.textOnly(t));
        }
        return photoCandidates(List.of(lines), hint);
    }

    /**
     * 把已上传照片的绝对 URL 解析到 ERP_UPLOAD_PATH 下的磁盘文件并读字节.
     * 安全: 只取 URL 里 {@code /upload/} 之后的相对路径, 归一化后必须仍在 upload 根下 (防目录穿越).
     *
     * @return 图片字节; 不可解析 / 越界 / 超大 / 读失败 → null
     */
    public byte[] loadImageFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        int idx = url.indexOf("/upload/");
        if (idx < 0) return null;
        String rel = url.substring(idx + "/upload/".length());
        // 去掉 query / hash
        int q = rel.indexOf('?');
        if (q >= 0) rel = rel.substring(0, q);
        int h = rel.indexOf('#');
        if (h >= 0) rel = rel.substring(0, h);
        if (rel.isEmpty()) return null;
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path target = root.resolve(rel).normalize();
            // 越界防护: 解析后的绝对路径必须仍在 upload 根目录内
            if (!target.startsWith(root)) {
                log.warn("[OCR] 拒绝越出上传根目录的路径: {}", abbreviate(url, 120));
                return null;
            }
            File f = target.toFile();
            if (!f.isFile()) return null;
            if (f.length() > MAX_IMAGE_BYTES) {
                log.warn("[OCR] 图片超过 {} 上限, 跳过: {}", MAX_IMAGE_BYTES, abbreviate(url, 120));
                return null;
            }
            return Files.readAllBytes(target);
        } catch (Exception e) {
            log.warn("[OCR] 读取照片失败 {}: {}", abbreviate(url, 120), e.getMessage());
            return null;
        }
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase();
        if (u.contains(".png")) return "image/png";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /** 提示词: 让视觉模型读原料标签上的编号/批号信息. */
    private static final String PROMPT =
            "这是一张食品级塑料原料 (如 LLDPE/PE 粒子袋) 的到货标签照片。"
                    + "请只读取标签上的「批号 / 生产批号 / 批次号 / LOT / BATCH」这类字母数字编号,"
                    + "外加「生产日期 / 到期日期 / 规格」如有也一并列出。"
                    + "每行输出一个识别到的值, 不要解释、不要翻译、不要编造;"
                    + "看不清的写「无法识别」。优先输出批号。";

    /** 品名提示最多送进模型的字符数 (防止超长商品名把提示词冲淡). */
    private static final int MAX_HINT_CHARS = 60;

    /**
     * 按本次识别目标构造提示词: 无品名 → 通用 PROMPT (行为与 v1.1.158 一致);
     * 有品名 → 只找该原料的标签, 其它原料标签上的编号一律不输出.
     */
    static String buildPrompt(String productName) {
        String hint = sanitizeHint(productName);
        if (hint.isEmpty()) return PROMPT;
        return "这是一张食品级塑料原料 (如 LLDPE/PE 粒子袋) 的到货标签照片, 照片里可能同时出现多种原料的标签。"
                + "本次只需要识别原料「" + hint + "」的标签,"
                + "请先在画面中找到品名与该原料相符 (简称/牌号一致即可) 的那张标签。"
                + "只读取这张标签上的「批号 / 生产批号 / 批次号 / LOT / BATCH」这类字母数字编号,"
                + "外加「生产日期 / 到期日期 / 规格」如有也一并列出。"
                + "其它原料标签上的编号不要输出。"
                + "每行输出一个识别到的值, 不要解释、不要翻译、不要编造;"
                + "画面里没有该原料的标签或看不清就只输出「无法识别」。优先输出批号。";
    }

    /**
     * 品名提示清洗: 压掉换行/多余空白并截断.
     * 品名来自商品档案 (可被录入), 直接拼进提示词等于把外部文本喂给模型 ——
     * 这里至少保证它不能伪造多行提示结构。
     */
    static String sanitizeHint(String productName) {
        if (productName == null) return "";
        String s = productName.replaceAll("[\\r\\n\\t]+", " ").trim();
        s = s.replaceAll("\\s{2,}", " ");
        return s.length() > MAX_HINT_CHARS ? s.substring(0, MAX_HINT_CHARS) : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
