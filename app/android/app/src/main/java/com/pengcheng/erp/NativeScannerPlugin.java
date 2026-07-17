package com.pengcheng.erp;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * v1.1.8+ 原生扫码桥接:
 *   启动自定义 ScannerActivity (而非 ZXing 的 CaptureActivity),
 *   ScannerActivity 顶部带返回按钮, 不扫码也能关闭界面.
 *   内部用 CaptureManager 处理相机生命周期和扫码结果, 扫码逻辑稳定.
 */
@CapacitorPlugin(name = "NativeScanner", requestCodes = { 8731 })
public class NativeScannerPlugin extends Plugin {
    public static final int REQUEST_CODE_SCAN = 8731;

    @PluginMethod
    public void startScan(PluginCall call) {
        // 必须先 saveCall, 否则 onActivityResult 拿不到 callback
        saveCall(call);
        Intent intent = new Intent(getActivity(), ScannerActivity.class);
        intent.putExtra("PROMPT_MESSAGE", "将条码/二维码放入框内");
        bridge.startActivityForPluginWithResult(call, intent, REQUEST_CODE_SCAN);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_SCAN) return;
        PluginCall call = getSavedCall();
        if (call == null) {
            android.util.Log.w("NativeScanner", "handleOnActivityResult: no saved call");
            return;
        }
        JSObject ret = new JSObject();
        if (resultCode == android.app.Activity.RESULT_OK) {
            String contents = null;
            if (data != null) {
                contents = data.getStringExtra("SCAN_RESULT");
            }
            if (contents == null || contents.isEmpty()) {
                ret.put("hasContent", false);
                ret.put("content", "");
            } else {
                ret.put("hasContent", true);
                ret.put("content", contents);
            }
        } else {
            ret.put("hasContent", false);
            ret.put("content", "");
        }
        call.resolve(ret);
    }
}
