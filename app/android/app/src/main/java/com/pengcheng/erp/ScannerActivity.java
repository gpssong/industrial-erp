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
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.Size;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.google.zxing.BarcodeFormat;

import java.util.EnumSet;

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

            // v1.1.64: 跟随父 Activity (竖屏) — 不再强制横屏.
            // 历史: 之前 setRequestedOrientation(SENSOR_LANDSCAPE) 让每次扫码都把手机转横,
            // 手持竖屏扫码入库/出库连续扫多个商品时, 每个都闪一次横屏宽界面, 体验差.
            // 扫码库位都是竖屏使用, 跟随父 Activity 的 SCREEN_ORIENTATION_UNSPECIFIED
            // (默认继承父 Activity 的 orientation, uni-app 主 Activity 竖屏) 即可.
            // applyCenterScanRect 用"短边 60%"计算方框, 竖屏时 = 宽 60%, 解码区不变.
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

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

            // ★★★ v1.1.63 灵敏度优化: 限定解码格式 + 锁定中央方框 + 连续对焦
            // ZXing 4.3.0 结构变化: DecoratedBarcodeView 是 FrameLayout, 真正的相机/解码
            // 在它内部的 BarcodeView (getBarcodeView()) 上. setDecoderFactory 在 Decorated
            // 上暴露, 但 setFramingRectSize / setCameraSettings 需在内部 BarcodeView 上调用.
            final BarcodeView inner = barcodeView.getBarcodeView();

            // 1) 限制解码格式 — 默认每帧跑 14 种 2D/1D 解码器, 拖慢 QR 响应 2-4x
            //    业务里扫码入库/出库几乎都是二维码, 1D 留个开关 (见 buildDecodeFormats())
            barcodeView.setDecoderFactory(new DefaultDecoderFactory(buildDecodeFormats()));

            // 2) 中央方框 — 把解码区限定在屏幕中间, 避免整帧解码 (尤其低端机拖速度)
            //    onCreate 时视图尺寸尚未 layout (width/height = 0), 实际调用在 onResume
            //    (首次进入 + 横竖屏切换时都会重新计算并 setFramingRectSize)

            // 3) 连续对焦 — 部分低端机默认 AUTO 会对焦卡住/拉风箱, 切 CONTINUOUS 解决
            //    ZXing 4.3.0 焦点通过 CameraSettings 配置. 在 onResume (camera 已启动)
            //    里再应用一次, 双保险. 失败不致命, 静默降级.
            try {
                CameraSettings cs = inner.getCameraSettings();
                cs.setContinuousFocusEnabled(true);
                cs.setFocusMode(CameraSettings.FocusMode.CONTINUOUS);
                inner.setCameraSettings(cs);
            } catch (Throwable ignored) {}

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
     * v1.1.63 灵敏度优化: 限制解码格式.
     *
     * 默认 ZXing 每帧图像要尝试解码 14 种 1D/2D 码 (EAN_13, CODE_128, QR, Aztec, DataMatrix, ...),
     * 业务里扫码入库/出库几乎全是 QR, 1D 解码器 (尤其 Code128 全帧扫描) 显著拖慢响应.
     * 限制到 {QR_CODE, EAN_13, EAN_8, CODE_128, CODE_39} 5 种:
     *   - QR: 90% 业务场景 (商品/库位二维码)
     *   - EAN_13/EAN_8: 部分供应商打的零售条码
     *   - CODE_128: 物流/外箱条码
     *   - CODE_39: 老式资产标签
     * 1D 解码比 QR 慢, 但保留兜底; 纯内部系统只用 QR 时可改成 {QR_CODE} 提速 2-4x.
     */
    private java.util.Set<BarcodeFormat> buildDecodeFormats() {
        return EnumSet.of(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39);
    }

    /**
     * v1.1.63 灵敏度优化: 限定中央扫描方框 (ZXing 4.3.0 API: setFramingRectSize).
     *
     * 把解码区缩小到屏幕短边的 60% 居中方框:
     *   - 解码算法只处理中央 36% 的像素, 计算量降 ~64%, 响应更快
     *   - 取景引导用户把码对准中央, 避免大尺寸 QR 贴边/超出
     * 视图尚未完成 layout (宽高 = 0) 时调用是 no-op (ZXing 内部用默认值).
     */
    private void applyCenterScanRect(DecoratedBarcodeView view) {
        // 方框尺寸在内部 BarcodeView 上设 (setFramingRectSize 是 CameraPreview 的方法,
        // ZXing 4.3.0 里 DecoratedBarcodeView 是 FrameLayout 包装, 实际逻辑在 inner view)
        BarcodeView inner = view.getBarcodeView();
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) return;
        int shortSide = Math.min(w, h);
        int side = (int) (shortSide * 0.60f);
        if (side <= 0) return;
        // Size(width, height) — v1.1.64 跟随竖屏: 竖屏时短边=宽, 中央 60% 方框.
        // 竖屏 1080x2340 → side=648, 方框 648x648 居中, 比横屏方框还小, 更聚焦
        inner.setFramingRectSize(new Size(side, side));
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
            // v1.1.63: onResume 时视图已完成 layout (宽高可用), 应用中央扫描方框
            applyCenterScanRect(barcodeView);
            // v1.1.63: 再次确认连续对焦 (相机在 onResume 后才 configureCamera, 设置要在此处生效)
            try {
                CameraSettings cs = barcodeView.getBarcodeView().getCameraSettings();
                cs.setContinuousFocusEnabled(true);
                cs.setFocusMode(CameraSettings.FocusMode.CONTINUOUS);
                barcodeView.getBarcodeView().setCameraSettings(cs);
            } catch (Throwable ignored) {}
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