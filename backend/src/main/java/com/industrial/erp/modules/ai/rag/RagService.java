package com.industrial.erp.modules.ai.rag;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * v1.1.75 任务3 — 轻量 RAG 文档问答 (本地切块 + 关键词/TF 相似度, 零外部向量库依赖).
 *
 * <p>语料: 启动时从 {@code ERP_RAG_CORPUS_DIR} 指向的目录读 *.md 文件 (默认 ./docs),
 * 按标题/段落切块, 内存检索 (按 TF 关键词命中打分)。后续可无缝换 pgvector/FAISS, 只改本类。
 *
 * <p>检索策略 (无 GPU/无向量库也能跑):
 * <ol>
 *   <li>把 query 切成关键词 (中文按 2 字滑窗 + 英文单词), 去重</li>
 *   <li>每个 chunk 算"命中关键词数 × 词频权重", 取 top-N</li>
 * </ol>
 * 语料 ~500KB、几百个 chunk, 内存检索足够, 无需外网。
 */
@Component
public class RagService implements InitializingBean {

    /** 语料目录 (env 可配, 默认 ./docs). 为空则 RAG 关闭 (检索返回空). */
    @Value("${erp.rag.corpus-dir:#{''}}")
    private String corpusDir;

    private final List<Chunk> chunks = new ArrayList<>();

    /** chunk: 一段带出处的文档片段. */
    public static class Chunk {
        String title;      // 来源文件
        String text;       // 片段正文
        List<String> tf;   // 该 chunk 里出现过的关键词 (小写, 用于打分)
        Chunk(String t, String x) { this.title = t; this.text = x; }
    }

    @Override
    public void afterPropertiesSet() {
        if (corpusDir == null || corpusDir.trim().isEmpty()) {
            return; // 没配语料目录 → RAG 不启用, 检索返回空, 不影响别的 AI 功能
        }
        Path dir = Paths.get(corpusDir);
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".md") || n.endsWith(".txt");
            }).forEach(files::add);
            for (Path f : files) {
                try {
                    String content = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    String name = f.getFileName().toString();
                    for (Chunk c : chunkize(name, content)) chunks.add(c);
                } catch (IOException ignore) { /* 单文件读失败跳过 */ }
            }
        } catch (IOException e) {
            chunks.clear();
        }
    }

    public boolean enabled() { return !chunks.isEmpty(); }
    public int size() { return chunks.size(); }

    /** 按 query 检索 topN 个相关片段. */
    public List<Chunk> search(String query, int topN) {
        if (!enabled() || query == null || query.trim().isEmpty()) return new ArrayList<>();
        List<String> kws = tokenize(query);
        if (kws.isEmpty()) return new ArrayList<>();

        // 统计每个关键词在全体 chunk 的文档频率 (IDF 近似, 提升精度)
        int total = chunks.size();
        List<Integer> df = new ArrayList<>();
        for (String kw : kws) {
            int c = 0;
            for (Chunk ch : chunks) if (ch.text.toLowerCase(Locale.ROOT).contains(kw)) c++;
            df.add(c);
        }

        List<int[]> scored = new ArrayList<>(); // [score, chunkIndex]
        for (int i = 0; i < chunks.size(); i++) {
            Chunk ch = chunks.get(i);
            String low = ch.text.toLowerCase(Locale.ROOT);
            double score = 0;
            for (int k = 0; k < kws.size(); k++) {
                String kw = kws.get(k);
                int hits = countOccurrences(low, kw);
                if (hits > 0) {
                    double idf = Math.log((total + 1.0) / (df.get(k) + 1.0));
                    score += (1 + Math.log(hits)) * idf;
                }
            }
            if (score > 0) scored.add(new int[]{ (int) Math.round(score * 100), i });
        }
        scored.sort(Comparator.comparingInt((int[] a) -> a[0]).reversed());
        List<Chunk> out = new ArrayList<>();
        for (int[] s : scored) {
            if (out.size() >= topN) break;
            out.add(chunks.get(s[1]));
        }
        return out;
    }

    /** 把命中片段拼成喂给 LLM 的上下文文本 (带出处). */
    public String contextFor(List<Chunk> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Chunk c : hits) {
            sb.append("[来源 ").append(c.title).append("]\n").append(c.text).append("\n\n");
            i++;
        }
        return sb.toString();
    }

    // ================= 切块 =================

    private List<Chunk> chunkize(String fileName, String content) {
        List<Chunk> out = new ArrayList<>();
        // 按 markdown 标题 (## / ### / 空段落) 切, 每块 ~600 字, 超过再滑窗
        String[] parts = content.split("\\n(?=#)");
        for (String p : parts) {
            String title = firstHeading(p, fileName);
            String body = p.trim();
            if (body.length() < 20) continue;
            int LIMIT = 600;
            if (body.length() <= LIMIT) {
                out.add(new Chunk(title, body));
            } else {
                for (int i = 0; i < body.length(); i += LIMIT / 2) {
                    int end = Math.min(body.length(), i + LIMIT);
                    out.add(new Chunk(title, body.substring(i, end)));
                    if (end == body.length()) break;
                }
            }
        }
        return out;
    }

    private static String firstHeading(String part, String fallback) {
        String line = part.trim().split("\\n")[0];
        if (line.startsWith("#")) return line.replaceFirst("^#+\\s*", "").trim() + " (" + fallback + ")";
        return fallback;
    }

    // ================= 分词 / 统计 =================

    /** 关键词: 英文单词 + 中文 2 字滑窗. */
    private static List<String> tokenize(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        List<String> kws = new ArrayList<>();
        // 英文单词
        for (String w : low.split("[^a-z0-9]+")) if (w.length() >= 2) kws.add(w);
        // 中文 2 字滑窗
        StringBuilder han = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) han.append(c);
            else { if (han.length() >= 2) slide(han, kws); han.setLength(0); }
        }
        if (han.length() >= 2) slide(han, kws);
        return kws.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    private static void slide(CharSequence s, List<String> out) {
        int len = s.length();
        if (len == 2) { out.add(s.toString()); return; }
        for (int i = 0; i + 2 <= len; i += 2) out.add(s.subSequence(i, i + 2).toString());
    }

    private static int countOccurrences(String hay, String needle) {
        int c = 0, idx = 0;
        while ((idx = hay.indexOf(needle, idx)) != -1) { c++; idx += needle.length(); }
        return c;
    }
}
