package com.pengcheng.erp;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * v1.1.8+ 自定义扫码 Activity, 继承自 android.app.Activity (而非 ZXing 的 CaptureActivity).
 *
 * 与 NativeScannerPlugin.startScan() 对接:
 *   - 扫码成功后设置 RESULT_OK + SCAN_RESULT 后 finish() 返回
 *   - 取消 (RESULT_CANCELED) 也通过 finish() 返回
 *
 * 与 ZXing CaptureActivity 的差异:
 *   - 加了一个顶部返回按钮, 用户不扫码也能关闭扫码界面
 *   - 不强依赖 ZXing 的 CaptureActivity (它的 layout 是 merge 标签, 不方便叠加 UI)
 *   - 沿用 CaptureManager 处理相机生命周期和扫码结果, 保证扫码逻辑稳定
 */
public class ScannerActivity extends Activity implements BarcodeCallback {

    private static final String TAG = "ScannerActivity";
    private CaptureManager captureManager;
    private DecoratedBarcodeView barcodeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // 全屏沉浸 (相机界面需要) - 必须在 setContentView 之前调用
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // 跟随用户 manifest 配置 (横屏, 与 ZXing CaptureActivity 一致)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

            setContentView(R.layout.activity_scanner);

            barcodeView = (DecoratedBarcodeView) findViewById(R.id.zxing_barcode_scanner);
            ImageButton btnBack = (ImageButton) findViewById(R.id.btn_back);
            TextView tvHint = (TextView) findViewById(R.id.tv_hint);

            // hint 从 intent extra 取, 兼容 NativeScannerPlugin 传的 PROMPT_MESSAGE
            Intent incoming = getIntent();
            if (incoming != null && incoming.hasExtra("PROMPT_MESSAGE")) {
                tvHint.setText(incoming.getStringExtra("PROMPT_MESSAGE"));
            }

            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                }
            });

            captureManager = new CaptureManager(this, barcodeView);
            captureManager.initializeFromIntent(incoming, savedInstanceState);

            // ★★★ 关键: 启动连续解码 (ZXing CaptureActivity 也会做这步)
            // BarcodeCallback 实现 barcodeResult() 回调, 扫码成功后 finish()
            barcodeView.decodeContinuous(this);
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed", t);
            Toast.makeText(this, "扫码启动失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    /**
     * 扫码成功回调 — ZXing 解析到条码/二维码后触发.
     * 设置 RESULT_OK + SCAN_RESULT 数据, finish() 返回到 NativeScannerPlugin.
     */
    @Override
    public void barcodeResult(BarcodeResult result) {
        Log.d(TAG, "Scanned: " + result.getText());
        // 停止连续解码, 避免重复回调
        barcodeView.pause();
        barcodeView.resume();

        Intent intent = new Intent();
        intent.putExtra("SCAN_RESULT", result.getText());
        intent.putExtra("SCAN_RESULT_FORMAT", result.getBarcodeFormat().toString());
        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    @Override
    public void possibleResultPoints(java.util.List<com.google.zxing.ResultPoint> points) {
        // 可选: 实时显示扫描框内的候选点
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (captureManager != null) {
                captureManager.onResume();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onResume failed", t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (captureManager != null) {
                captureManager.onPause();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onPause failed", t);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (captureManager != null) {
                captureManager.onDestroy();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy failed", t);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            if (captureManager != null) {
                captureManager.onSaveInstanceState(outState);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onSaveInstanceState failed", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        try {
            if (captureManager != null) {
                captureManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onRequestPermissionsResult failed", t);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}