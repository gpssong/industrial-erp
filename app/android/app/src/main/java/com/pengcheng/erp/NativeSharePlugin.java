package com.pengcheng.erp;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.1.29+ 原生分享 PDF (绕开 uni-app saveFile 返回 webview blob 路径的坑):
 *   - JS 端只传 PDF 的 HTTP URL 和 share 元数据
 *   - 原生 plugin 自己用 HttpURLConnection 下载到 Context.getFilesDir()/share-<ts>.pdf
 *   - 用 FileProvider.getUriForFile 转 content:// URI
 *   - 拼 ACTION_SEND + EXTRA_STREAM 启 share sheet (微信/QQ/邮件附件)
 *
 *  为何不直接接收 filePath?
 *   - uni.saveFile 在 App-Plus 上返回 blob:http://localhost/UUID... 这种 webview 虚拟路径,
 *     无法解析为真实磁盘文件, FileProvider 找不到 root
 *   - plus.io 转换也不稳定 (有时返回 file://localhost/UUID)
 *
 *  调用: NativeShare.sharePdf({ url, title, text })
 */
@CapacitorPlugin(name = "NativeShare")
public class NativeSharePlugin extends Plugin {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void sharePdf(PluginCall call) {
        String url = call.getString("url");
        String title = call.getString("title", "");
        String text = call.getString("text", "");
        if (url == null || url.isEmpty()) {
            call.reject("url is required");
            return;
        }
        // v1.1.29+: 用 saveCall 保持 call 引用, 在 AsyncTask 完成后 resolve
        saveCall(call);
        // 后台线程下载 PDF
        exec.submit(() -> {
            try {
                File pdfFile = downloadPdf(url);
                if (pdfFile == null || !pdfFile.exists()) {
                    PluginCall saved = getSavedCall();
                    if (saved != null) {
                        saved.reject("download failed: " + url);
                    }
                    return;
                }
                shareIntent(pdfFile, title, text);
                PluginCall saved = getSavedCall();
                if (saved != null) {
                    JSObject ret = new JSObject();
                    ret.put("shared", true);
                    ret.put("path", pdfFile.getAbsolutePath());
                    saved.resolve(ret);
                }
            } catch (Exception ex) {
                PluginCall saved = getSavedCall();
                if (saved != null) {
                    saved.reject("share failed: " + ex.getMessage(), ex);
                }
            }
        });
    }

    private File downloadPdf(String urlStr) throws Exception {
        File shareDir = new File(getContext().getFilesDir(), "share");
        if (!shareDir.exists()) shareDir.mkdirs();
        File pdfFile = new File(shareDir, "share-" + System.currentTimeMillis() + ".pdf");

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("GET");
        // v1.1.29+: 携带 cookie 让后端 Sa-Token 鉴权通过
        String cookie = android.webkit.CookieManager.getInstance().getCookie(urlStr);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(pdfFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        conn.disconnect();
        return pdfFile;
    }

    private void shareIntent(File file, String title, String text) {
        // v1.1.29+: FileProvider.getUriForFile 需要 files-path/external-path 等 root 已声明
        // (见 res/xml/file_paths.xml: external-path / cache-path / files-path / external-cache-path)
        Uri fileUri = FileProvider.getUriForFile(
            getContext(),
            getContext().getPackageName() + ".fileprovider",
            file
        );

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, fileUri);
        if (title != null && !title.isEmpty()) {
            intent.putExtra(Intent.EXTRA_SUBJECT, title);
        }
        if (text != null && !text.isEmpty()) {
            intent.putExtra(Intent.EXTRA_TEXT, text);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(intent, title != null && !title.isEmpty() ? title : "分享 PDF");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getActivity().startActivity(chooser);
    }
}
