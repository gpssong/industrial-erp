package com.pengcheng.erp;

import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.WebView;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * v1.1.153 — 原生系统打印.
 *
 * <p>Capacitor WebView 里 {@code window.print()} 不会弹系统打印面板 (WebView 没实现),
 * App「XK 单据打印」在 APK 里就只能提示去 PC. 这里用 Android {@link PrintManager} +
 * WebView 自带的 {@link WebView#createPrintDocumentAdapter} 把当前 WebView 内容交给
 * 系统打印服务: 页面 CSS 的 {@code @media print} 负责只留纸面, 用户在面板里可直连
 * 打印机或「另存为 PDF」.
 *
 * <p>调用: {@code NativePrint.printPage({ jobName })} (JS 端仅在 isNativeApp() 时调用).
 */
@CapacitorPlugin(name = "NativePrint")
public class NativePrintPlugin extends Plugin {

    @PluginMethod
    public void printPage(PluginCall call) {
        String jobName = call.getString("jobName", "XK 受控记录");
        // PrintManager.print / createPrintDocumentAdapter 都必须在主线程
        getActivity().runOnUiThread(() -> {
            try {
                WebView webView = getBridge().getWebView();
                if (webView == null) {
                    call.reject("WebView 未就绪, 无法打印");
                    return;
                }
                PrintManager printManager = (PrintManager) getContext().getSystemService(Context.PRINT_SERVICE);
                if (printManager == null) {
                    call.reject("当前设备不支持系统打印服务");
                    return;
                }
                PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
                // 不设 NO_MARGINS: 交给打印机/预览默认边距, 页面里已有留白, 避免贴边
                PrintAttributes attributes = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build();
                printManager.print(jobName, adapter, attributes);
                call.resolve();
            } catch (Exception e) {
                call.reject("调起系统打印失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }
}
