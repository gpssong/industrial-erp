package com.industrial.erp.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.industrial.erp.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Tag(name = "文件上传")
@RestController
@RequestMapping("/system/upload")
@SaCheckLogin
public class SysUploadController {

    // 优先用 ERP_UPLOAD_PATH 环境变量, 否则用 user.dir 下的 upload 目录
    private final String uploadDir = (System.getenv("ERP_UPLOAD_PATH") != null
            && !System.getenv("ERP_UPLOAD_PATH").isEmpty())
            ? System.getenv("ERP_UPLOAD_PATH") + "/"
            : System.getProperty("user.dir") + "/upload/";

    // 允许上传的文件后缀 (CVE 防御: 禁止可执行/脚本类后缀, 避免上传被解析执行)
    private static final Set<String> ALLOWED_EXTS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp",
            ".pdf", ".xls", ".xlsx", ".doc", ".docx"
    );

    // S3 安全红线: 文件大小上限 10MB (业务上证书/图片/合同均足够, 拦截巨型文件 DoS / 撑爆磁盘)
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;

    @Operation(summary = "上传文件 (图片/文档)")
    @PostMapping("/file")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         HttpServletRequest request) throws IOException {
        if (file.isEmpty()) throw new RuntimeException("文件为空");
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("文件超过 10MB 上限, 当前=" + file.getSize() + " 字节");
        }

        // 根据日期分目录: yyyyMM/dd
        java.time.LocalDate today = java.time.LocalDate.now();
        String dateDir = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String dayDir = String.format("%02d", today.getDayOfMonth());

        File dir = new File(uploadDir + dateDir + "/" + dayDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("无法创建上传目录");
        }

        // 生成唯一文件名
        String original = file.getOriginalFilename();
        String ext = original != null && original.contains(".") ? original.substring(original.lastIndexOf(".")) : "";
        String lowerExt = ext.toLowerCase();
        if (!ALLOWED_EXTS.contains(lowerExt)) {
            throw new RuntimeException("不支持的文件类型: " + ext);
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;

        Path targetPath = Paths.get(dir.getAbsolutePath(), filename);
        Files.copy(file.getInputStream(), targetPath);

        // 返回绝对地址: App 原生端把 fileUrl 直接塞进 <image :src> (in.vue / pat.vue),
        // 根相对地址在 Capacitor/WebView 里会解析到本地协议 → 图裂; 因此沿用绝对 URL。
        // 端口必须保留: nginx 侧要转发 Host $http_host (用 $host 会丢 :8088)。
        String url = absoluteBase(request) + request.getContextPath()
                + "/upload/" + dateDir + "/" + dayDir + "/" + filename;

        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("filename", filename);
        data.put("size", file.getSize());
        data.put("contentType", file.getContentType());
        // 统一 R 信封: PC 拦截器只放行 code=200, App upload.js 也按 {code,data.url} 解析
        return R.ok(data);
    }

    // 优先 X-Forwarded-* (nginx 反代后才拿得到原始 host/端口/协议), 否则退回请求本身
    private String absoluteBase(HttpServletRequest request) {
        String proto = firstToken(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstToken(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null || host.isEmpty()) {
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(proto) && port == 80) || ("https".equals(proto) && port == 443);
            host = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return proto + "://" + host;
    }

    private static String firstToken(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String v = value.trim();
        int comma = v.indexOf(',');
        return (comma > 0 ? v.substring(0, comma) : v).trim();
    }
}
