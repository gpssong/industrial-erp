package com.industrial.erp.modules.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Tag(name = "文件上传")
@RestController
@RequestMapping("/system/upload")
public class SysUploadController {

    // 优先用 ERP_UPLOAD_PATH 环境变量, 否则用 user.dir 下的 upload 目录
    private final String uploadDir = (System.getenv("ERP_UPLOAD_PATH") != null
            && !System.getenv("ERP_UPLOAD_PATH").isEmpty())
            ? System.getenv("ERP_UPLOAD_PATH") + "/"
            : System.getProperty("user.dir") + "/upload/";

    @Operation(summary = "上传文件 (图片/文档)")
    @PostMapping("/file")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new RuntimeException("文件为空");

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
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;

        Path targetPath = Paths.get(dir.getAbsolutePath(), filename);
        Files.copy(file.getInputStream(), targetPath);

        // 返回访问路径 (相对路径)
        String url = "/upload/" + dateDir + "/" + dayDir + "/" + filename;

        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("filename", filename);
        data.put("size", file.getSize());
        data.put("contentType", file.getContentType());
        return data;
    }
}
