package com.pengcheng.erp;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * v1.1.8+ 原生扫码桥接: 直接启动 ZXing 的 CaptureActivity 全屏 Activity,
 * 完全绕过 @capacitor-community/barcode-scanner 的 WebView 叠加方案 (该方案有 BarcodeView 0 尺寸 bug).
 */
@CapacitorPlugin(name = "NativeScanner", requestCodes = { 8731 })
public class NativeScannerPlugin extends Plugin {
    public static final int REQUEST_CODE_SCAN = 8731;

    @PluginMethod
    public void startScan(PluginCall call) {
        // 必须先 saveCall, 否则 onActivityResult 拿不到 callback
        saveCall(call);
        Intent intent = new Intent(getActivity(), CaptureActivity.class);
        intent.putExtra("PROMPT_MESSAGE", "将条码/二维码放入框内");
        // 不要 addFlags CLEAR_TOP, 否则 ZXing CaptureActivity 返回时 data 可能是 null
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
