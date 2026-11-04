package com.pengcheng.erp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.167 — 标签打印机蓝牙直发 (Android only).
 *
 * <p>本类是 docs/32 §9 真机取证的工具, <b>不含任何指令集知识</b>: {@link #print} 收到的就是调用方
 * 给它的字节流, 原样分片写入。TSPL/CPCL 的拼装在服务端渲染器里, 取证通过后才写。
 *
 * <p>分片是<b>必须</b>的: BLE 单次写超过 (mtu-3) 字节会被协议栈静默丢弃, 表现是
 * 「标签打一半」而不是报错 —— 所以 print 逐片 write-with-response, 每片等
 * {@code onCharacteristicWrite} 再发下一片。
 *
 * <p><b>与 docs/33 计划骨架的四处差异</b> (两处是控制者裁定的缺陷修补, 两处是被裁定补上的前置条件;
 * 桥面五个方法的名称/返回键语义全部未动):
 * <ol>
 *   <li><b>§a 运行时权限</b> —— 计划骨架只有 manifest 声明, 全仓没有任何运行时权限流程
 *       ({@code @Permission} / {@code requestPermissionForAlias} / {@code checkSelfPermission}
 *       在改动前是零命中)。API 31+ 上 {@code startScan}/{@code connectGatt} 会抛
 *       {@code SecurityException}, 而骨架的 catch 只会回一句「缺少蓝牙扫描权限」且用户
 *       <b>没有任何入口去授权</b> —— 取证 APK 在现代手机上直接是死的。故加 {@code ble}/{@code bleLegacy}
 *       两个别名 + {@link #ensurePermissions} 方法, 并在 scan/connect/print 前设闸。</li>
 *   <li><b>§b 片大小</b> —— 骨架的 {@code writeNextChunk()} 无参重载把每片大小写回 20,
 *       协商到的 MTU 被静默忽略 → 取证记录的「分片行为」根本不代表真实 MTU。改为把
 *       perWrite/delayMs 存成字段, 由 {@code onCharacteristicWrite} 续片时沿用。</li>
 *   <li><b>§c 写超时兜底</b> —— 骨架的 1500ms 兜底会把<b>已被 ack 的那一片重发一次</b> →
 *       打印机收到重复字节, 在取证里会被误读成「指令集行为异常」。改为按片号追踪在途状态:
 *       超时只放行下一片, 绝不重发在途片, 并把「没等到回调」的片数如实报出来。</li>
 *   <li><b>§d MTU 协商</b> —— 骨架从不 {@code requestMtu} → {@code mtu} 恒为 23, §b 修了也没数据可用。
 *       连接后主动协商 MTU (失败/超时都回落 23, 不影响 connect 完成)。</li>
 * </ol>
 */
@CapacitorPlugin(
        name = "LabelBle",
        permissions = {
                // API 31+ (Android 12): 运行时权限, 必须弹框申请
                @Permission(
                        strings = {
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                        },
                        alias = "ble"
                ),
                // API ≤30: BLUETOOTH/BLUETOOTH_ADMIN 是 install-time (随装即得),
                // 但 BLE 扫描在 API 23+ 要求 ACCESS_FINE_LOCATION —— 这才是真正要申请的那个。
                @Permission(
                        strings = {
                                Manifest.permission.BLUETOOTH,
                                Manifest.permission.BLUETOOTH_ADMIN,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        alias = "bleLegacy"
                )
        }
)
public class LabelBlePlugin extends Plugin {

    private static final String TAG = "LabelBle";

    private static final int DEFAULT_SCAN_MS = 6000;
    private static final int MAX_SCAN_MS = 30000;
    /** BLE 链路层默认 MTU 23, 扣掉 3 字节 ATT 头 = 单片 20 字节 —— 与计划骨架的 WRITE_CHUNK 同值 */
    private static final int DEFAULT_MTU = 23;
    /** 片大小下限: 只在 MTU 小得离谱时兜底, 绝不把片大小抬到 (mtu-3) 以上 */
    private static final int MIN_WRITE_CHUNK = 1;
    private static final int MAX_WRITE_CHUNK = 180;
    /** 连接后向设备申请的 MTU; 设备可以拒绝并回一个更小的值, 以 onMtuChanged 为准 */
    private static final int REQUEST_MTU = 247;
    /** 单片等 onCharacteristicWrite 的上限; 超时只放行下一片, 不重发 (见类头 §c) */
    private static final long WRITE_ACK_TIMEOUT_MS = 1500L;
    /** MTU 协商无回调时的兜底, 到点直接 discoverServices, 不让 connect 挂死 */
    private static final long MTU_NEGOTIATION_TIMEOUT_MS = 1500L;
    /** 整条连接链 (connectGatt → MTU → 服务发现) 的兜底, 防 H5 侧 promise 永挂 */
    private static final long CONNECT_TIMEOUT_MS = 12000L;

    // ── 权限 (§a) ────────────────────────────────────────────────
    /** 记是谁触发了授权弹框, 回调回来后据此续跑 */
    private String permissionOrigin;

    // ── 扫描 ─────────────────────────────────────────────────────
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Runnable scanTimeout;
    private PluginCall pendingScan;
    /** mac → 设备, 扫描期间累积 */
    private final Map<String, BluetoothDevice> discovered = new HashMap<>();
    /** mac → 最后一次上报的 rssi */
    private final Map<String, Integer> discoveredRssi = new HashMap<>();
    private final List<String> discoveredOrder = new ArrayList<>();

    // ── 连接 ─────────────────────────────────────────────────────
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private PluginCall pendingConnect;
    private String connectedMac;
    private String connectedName;
    private int mtu = DEFAULT_MTU;
    private boolean mtuRequested;
    private boolean servicesRequested;
    private Runnable mtuFallback;
    private Runnable connectTimeout;

    // ── 写入 (§b/§c) ─────────────────────────────────────────────
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PluginCall pendingWrite;
    private byte[] writeBuf;
    private int writeOffset;
    private int writeChunkCount;
    /** 没等到 onCharacteristicWrite 回调的片数 (取证要看这个数) */
    private int writeMissingAcks;
    private int writePerWrite;
    private long writeDelayMs;
    /** 已发出的片号 */
    private int writeSeq;
    /** 正在等 ack 的片号, 0 = 没有在途片 */
    private int inFlightSeq;
    private Runnable ackFallback;
    private boolean writeScheduled;

    private BluetoothAdapter adapter() {
        if (adapter != null) return adapter;
        BluetoothManager mgr = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = mgr == null ? null : mgr.getAdapter();
        return adapter;
    }

    // ══════════════════════════════════════════════════════════════
    // §a 运行时权限
    // ══════════════════════════════════════════════════════════════

    private static boolean isApi31Plus() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private boolean granted(String permission) {
        return ActivityCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 本插件跑起来所需 permissions 是否齐。
     * API 31+ 看 BLUETOOTH_SCAN + BLUETOOTH_CONNECT;
     * API ≤30 只看 ACCESS_FINE_LOCATION (BLUETOOTH/ADMIN 是 normal 权限, 装即得)。
     */
    private boolean hasBlePermission() {
        if (isApi31Plus()) {
            return granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private String bleAlias() {
        return isApi31Plus() ? "ble" : "bleLegacy";
    }

    private String deniedMessage() {
        return isApi31Plus()
                ? "未授予蓝牙权限 (Android 12+ 需要「附近的设备」: BLUETOOTH_SCAN / BLUETOOTH_CONNECT), 请在系统设置 → 应用 → 权限里允许"
                : "未授予定位权限 (Android 11 及以下 BLE 扫描必须有 ACCESS_FINE_LOCATION), 请在系统设置里允许";
    }

    /**
     * 权限闸门: 已授权返回 true 继续跑; 未授权则弹系统授权框并把这次 call 交给
     * {@link #blePermissionResult} 续跑, 返回 false 让调用方直接 return。
     */
    private boolean ensureBlePermission(PluginCall call, String origin) {
        if (hasBlePermission()) return true;
        permissionOrigin = origin;
        requestPermissionForAlias(bleAlias(), call, "blePermissionResult");
        return false;
    }

    /** H5 侧主动申请权限用 (调试页第一颗按钮); 不在五个桥面方法之内, 后续任务不消费它。 */
    @PluginMethod
    public void ensurePermissions(PluginCall call) {
        if (hasBlePermission()) {
            call.resolve(permissionPayload(true));
            return;
        }
        permissionOrigin = "ensure";
        requestPermissionForAlias(bleAlias(), call, "blePermissionResult");
    }

    private JSObject permissionPayload(boolean grantedNow) {
        JSObject o = new JSObject();
        o.put("granted", grantedNow);
        o.put("apiLevel", Build.VERSION.SDK_INT);
        o.put("alias", bleAlias());
        return o;
    }

    /**
     * Capacitor 的授权结果回调。注意 {@code Bridge.validatePermissions} 只校验「清单里声明过」,
     * <b>用户拒绝也会走到这里</b>, 所以必须自己再查一次真实授权状态。
     */
    @PermissionCallback
    public void blePermissionResult(PluginCall call) {
        if (call == null) return;
        String origin = permissionOrigin;
        permissionOrigin = null;
        if (!hasBlePermission()) {
            call.reject(deniedMessage());
            return;
        }
        if ("scan".equals(origin)) {
            doScan(call);
        } else if ("connect".equals(origin)) {
            doConnect(call);
        } else if ("print".equals(origin)) {
            doPrint(call);
        } else {
            call.resolve(permissionPayload(true));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // scan
    // ══════════════════════════════════════════════════════════════

    @PluginMethod
    public void scan(PluginCall call) {
        BluetoothAdapter a = adapter();
        if (a == null) { call.reject("本机不支持蓝牙"); return; }
        if (!a.isEnabled()) { call.reject("蓝牙未开启, 请先在系统设置里打开蓝牙"); return; }
        if (pendingScan != null) { call.reject("上一次扫描还没结束, 请等它跑完"); return; }
        if (!ensureBlePermission(call, "scan")) return;
        doScan(call);
    }

    private void doScan(PluginCall call) {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { call.reject("蓝牙未开启"); return; }
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) { call.reject("无法取得 BLE 扫描器"); return; }

        discovered.clear();
        discoveredRssi.clear();
        discoveredOrder.clear();
        pendingScan = call;
        Integer timeoutArg = call.getInt("timeoutMs");
        long timeout = timeoutArg == null ? DEFAULT_SCAN_MS : Math.max(500, Math.min(timeoutArg, MAX_SCAN_MS));

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                if (d == null || d.getAddress() == null) return;
                if (!discovered.containsKey(d.getAddress())) discoveredOrder.add(d.getAddress());
                discovered.put(d.getAddress(), d);
                discoveredRssi.put(d.getAddress(), result.getRssi());
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "BLE 扫描失败 errorCode=" + errorCode);
                PluginCall c = pendingScan;
                pendingScan = null;
                stopScanQuietly();
                if (c != null) c.reject("BLE 扫描失败 (errorCode=" + errorCode
                        + ") — 通常是系统蓝牙没开或扫描过于频繁, 稍后再试");
            }
        };
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException e) {
            pendingScan = null;
            scanCallback = null;
            call.reject("缺少蓝牙扫描权限: " + e.getMessage() + " — 请点「申请蓝牙权限」", e);
            return;
        } catch (IllegalArgumentException | IllegalStateException e) {
            pendingScan = null;
            scanCallback = null;
            call.reject("启动 BLE 扫描失败: " + e.getMessage(), e);
            return;
        }
        scanTimeout = () -> {
            PluginCall c = pendingScan;
            pendingScan = null;
            stopScanQuietly();
            if (c == null) return;
            JSArray arr = new JSArray();
            for (String mac : discoveredOrder) {
                BluetoothDevice d = discovered.get(mac);
                String name = null;
                try { name = d.getName(); } catch (SecurityException ignored) { }
                JSObject o = new JSObject();
                o.put("mac", mac);
                o.put("name", name == null ? "" : name);
                Integer rssi = discoveredRssi.get(mac);
                o.put("rssi", rssi == null ? 0 : rssi);
                arr.put(o);
            }
            JSObject ret = new JSObject();
            ret.put("devices", arr);
            c.resolve(ret);
        };
        ui.postDelayed(scanTimeout, timeout);
    }

    private void stopScanQuietly() {
        if (scanTimeout != null) {
            ui.removeCallbacks(scanTimeout);
            scanTimeout = null;
        }
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanCallback = null;
    }

    // ══════════════════════════════════════════════════════════════
    // connect
    // ══════════════════════════════════════════════════════════════

    @PluginMethod
    public void connect(PluginCall call) {
        String mac = call.getString("mac");
        if (mac == null || mac.trim().isEmpty()) { call.reject("缺少 mac"); return; }
        BluetoothAdapter a = adapter();
        if (a == null) { call.reject("本机不支持蓝牙"); return; }
        if (!a.isEnabled()) { call.reject("蓝牙未开启"); return; }
        if (!ensureBlePermission(call, "connect")) return;
        doConnect(call);
    }

    private void doConnect(PluginCall call) {
        String mac = call.getString("mac");
        if (mac == null || mac.trim().isEmpty()) { call.reject("缺少 mac"); return; }
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { call.reject("蓝牙未开启"); return; }
        BluetoothDevice device;
        try {
            device = a.getRemoteDevice(mac.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            call.reject("MAC 地址非法: " + mac);
            return;
        }
        if (pendingConnect != null) {
            PluginCall stale = pendingConnect;
            pendingConnect = null;
            stale.reject("被新的连接请求取代");
        }
        if (pendingWrite != null) failWrite("连接重置, 上一笔发送中止");
        pendingConnect = call;
        writeChar = null;
        mtu = DEFAULT_MTU;
        mtuRequested = false;
        servicesRequested = false;
        connectedMac = device.getAddress();
        connectedName = null;
        try { connectedName = device.getName(); } catch (SecurityException ignored) { }
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
        try {
            gatt = device.connectGatt(getContext(), false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            pendingConnect = null;
            call.reject("缺少蓝牙连接权限: " + e.getMessage() + " — 请点「申请蓝牙权限」", e);
            return;
        } catch (IllegalArgumentException e) {
            pendingConnect = null;
            call.reject("连接失败: " + e.getMessage(), e);
            return;
        }
        // 兜底: 设备关机/被占用时 connectGatt 可以整条链一个回调都不来, 不加这道闸门 H5 的 promise 就永挂
        cancelConnectTimeout();
        connectTimeout = () -> {
            connectTimeout = null;
            PluginCall c = pendingConnect;
            pendingConnect = null;
            if (c == null) return;
            Log.w(TAG, "连接超时 mac=" + mac + " — 无任何 GATT 回调");
            c.reject("连接超时 (" + CONNECT_TIMEOUT_MS + "ms 无响应) — 确认打印机已开机、且没有被手机系统蓝牙配对占用");
        };
        ui.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui.post(() -> onConnected(g));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ui.post(() -> onDisconnected(status));
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            ui.post(() -> {
                mtuRequested = false;
                cancelMtuFallback();
                if (status == BluetoothGatt.GATT_SUCCESS && newMtu >= DEFAULT_MTU) {
                    mtu = newMtu;
                }
                Log.i(TAG, "MTU 协商结果 newMtu=" + newMtu + " status=" + status + " → 采用 " + mtu);
                discoverServicesSafely(g);
                // 不在这里 resolve: 服务没发现完就没有可写特征, connect 的返回值要说得出「能不能写」
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            ui.post(() -> {
                cancelConnectTimeout();
                BluetoothGattCharacteristic found = pickWriteCharacteristic(g);
                PluginCall c = pendingConnect;
                pendingConnect = null;
                if (found == null) {
                    if (c != null) c.reject("已连接但该设备没有可写特征 — 可能是经典 SPP 打印机, 需换传输方式");
                    return;
                }
                writeChar = found;
                if (c != null) resolveConnect(c, g);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            ui.post(() -> onWriteAck(status));
        }
    };

    private void onConnected(BluetoothGatt g) {
        // 先协商 MTU 再发现服务: 单片能写多少字节完全由 MTU 决定 (类头 §d)
        try {
            mtuRequested = g.requestMtu(REQUEST_MTU);
        } catch (SecurityException e) {
            Log.w(TAG, "requestMtu 缺权限", e);
            mtuRequested = false;
        }
        if (!mtuRequested) {
            discoverServicesSafely(g);
            return;
        }
        mtuFallback = () -> {
            if (!mtuRequested) return;
            mtuRequested = false;
            mtuFallback = null;
            Log.w(TAG, "onMtuChanged 未回调, 按默认 MTU=" + mtu + " 继续发现服务");
            discoverServicesSafely(g);
        };
        ui.postDelayed(mtuFallback, MTU_NEGOTIATION_TIMEOUT_MS);
    }

    private void cancelMtuFallback() {
        if (mtuFallback != null) {
            ui.removeCallbacks(mtuFallback);
            mtuFallback = null;
        }
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) {
            ui.removeCallbacks(connectTimeout);
            connectTimeout = null;
        }
    }

    private void discoverServicesSafely(BluetoothGatt g) {
        if (servicesRequested) return;
        servicesRequested = true;
        try {
            g.discoverServices();
        } catch (SecurityException e) {
            Log.w(TAG, "discoverServices 缺 BLUETOOTH_CONNECT 权限", e);
            pendingConnect = nullSafeReject(pendingConnect, "缺少蓝牙连接权限: " + e.getMessage());
        }
    }

    private PluginCall nullSafeReject(PluginCall c, String msg) {
        if (c != null) c.reject(msg);
        return null;
    }

    private void onDisconnected(int status) {
        cancelMtuFallback();
        cancelConnectTimeout();
        mtuRequested = false;
        servicesRequested = false;
        writeChar = null;
        inFlightSeq = 0;
        if (ackFallback != null) {
            ui.removeCallbacks(ackFallback);
            ackFallback = null;
        }
        PluginCall c = pendingConnect;
        pendingConnect = null;
        if (c != null) {
            c.reject("连接失败或已断开 (status=" + status + ") — 若打印机是经典蓝牙 SPP 而非 BLE, 换传输方式");
        }
        if (pendingWrite != null) {
            failWrite("打印机在第 " + writeChunkCount + " 片后断开 (gattStatus=" + status
                    + "), 已送出 " + writeOffset + "/" + (writeBuf == null ? 0 : writeBuf.length) + " 字节");
        }
    }

    /** 连接完成 = 服务发现回调已到且挑到了可写特征 (见 onServicesDiscovered)。 */
    private void resolveConnect(PluginCall c, BluetoothGatt g) {
        if (connectedName == null || connectedName.isEmpty()) {
            try {
                if (g.getDevice() != null && g.getDevice().getName() != null) {
                    connectedName = g.getDevice().getName();
                }
            } catch (SecurityException ignored) { }
        }
        JSObject ret = new JSObject();
        ret.put("connected", true);
        ret.put("mtu", mtu);
        ret.put("mac", connectedMac == null ? JSONObject.NULL : connectedMac);
        ret.put("name", connectedName == null || connectedName.isEmpty() ? JSONObject.NULL : connectedName);
        // 桥面五方法的键名/语义未动; 下面两个是取证用的附加键, 下游不读也不影响
        ret.put("chunkSize", Math.min(MAX_WRITE_CHUNK, Math.max(MIN_WRITE_CHUNK, mtu - 3)));
        ret.put("serviceCount", g.getServices() == null ? 0 : g.getServices().size());
        c.resolve(ret);
    }

    /** 标签打印机各家自定义服务 UUID 不一, 取证阶段先「取第一个可写特征」并打日志。 */
    private BluetoothGattCharacteristic pickWriteCharacteristic(BluetoothGatt g) {
        if (g.getServices() == null) return null;
        for (BluetoothGattService svc : g.getServices()) {
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                int props = ch.getProperties();
                boolean writable = (props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                if (writable) {
                    Log.i(TAG, "选用可写特征 service=" + svc.getUuid() + " char=" + ch.getUuid()
                            + " props=" + props);
                    return ch;
                }
            }
        }
        Log.i(TAG, "未发现任何可写特征, 服务清单=" + g.getServices().size() + " 个");
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    // print
    // ══════════════════════════════════════════════════════════════

    @PluginMethod
    public void print(PluginCall call) {
        if (gatt == null || writeChar == null) { call.reject("尚未连接打印机"); return; }
        if (pendingWrite != null) { call.reject("上一笔还没发完, 请等待或先断开"); return; }
        if (!ensureBlePermission(call, "print")) return;
        doPrint(call);
    }

    private void doPrint(PluginCall call) {
        if (gatt == null || writeChar == null) { call.reject("尚未连接打印机"); return; }
        String b64 = call.getString("contentBase64");
        if (b64 == null || b64.isEmpty()) { call.reject("内容为空"); return; }
        byte[] bytes;
        try {
            bytes = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("contentBase64 不是合法 base64");
            return;
        }
        if (bytes.length == 0) { call.reject("内容为空"); return; }

        // §b: 片大小 = 有效 MTU - 3。调用方显式给的 mtu 优先, 否则用协商到的 mtu。
        //     绝不把片大小抬到 mtu-3 以上 —— 超了会被协议栈静默丢弃 (标签打一半)。
        Integer mtuArg = call.getInt("mtu");
        int effectiveMtu = (mtuArg != null && mtuArg > 3) ? mtuArg : mtu;
        writePerWrite = Math.min(MAX_WRITE_CHUNK, Math.max(MIN_WRITE_CHUNK, effectiveMtu - 3));
        Integer delay = call.getInt("chunkDelayMs");
        writeDelayMs = delay == null ? 20L : Math.max(0, Math.min(delay, 200));

        writeBuf = bytes;
        writeOffset = 0;
        writeChunkCount = 0;
        writeMissingAcks = 0;
        writeSeq = 0;
        inFlightSeq = 0;
        writeScheduled = false;
        pendingWrite = call;
        Log.i(TAG, "开始发送 " + bytes.length + " 字节: mtu=" + effectiveMtu
                + " 片大小=" + writePerWrite + " 片间隔=" + writeDelayMs + "ms");
        scheduleNextSlice();
    }

    /** 安排下一片 (片间隔在这里生效)。inFlightSeq != 0 时不发, 保证一次只有一片在途。 */
    private void scheduleNextSlice() {
        if (pendingWrite == null || writeScheduled || inFlightSeq != 0) return;
        if (writeBuf == null || writeOffset >= writeBuf.length) {
            finishWrite();
            return;
        }
        writeScheduled = true;
        ui.postDelayed(this::issueNextSlice, writeDelayMs);
    }

    private void issueNextSlice() {
        writeScheduled = false;
        PluginCall c = pendingWrite;
        if (c == null || writeBuf == null || inFlightSeq != 0) return;
        if (writeOffset >= writeBuf.length) {
            finishWrite();
            return;
        }
        int end = Math.min(writeBuf.length, writeOffset + writePerWrite);
        byte[] slice = Arrays.copyOfRange(writeBuf, writeOffset, end);
        writeOffset = end;
        writeChunkCount++;
        final int mySeq = ++writeSeq;
        inFlightSeq = mySeq;
        writeChar.setValue(slice);
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok;
        try {
            ok = gatt.writeCharacteristic(writeChar);
        } catch (SecurityException e) {
            failWrite("缺少蓝牙写权限: " + e.getMessage() + " — 请点「申请蓝牙权限」");
            return;
        } catch (IllegalArgumentException | IllegalStateException e) {
            failWrite("BLE 写入异常 (第 " + mySeq + " 片): " + e.getMessage());
            return;
        }
        if (!ok) {
            failWrite("BLE 写队列忙, 在第 " + mySeq + " 片失败 — 请调大 chunkDelayMs 后重试");
            return;
        }
        // §c: 兜底只在「回调真的没来」时放行下一片, 绝不重发这一片。
        ackFallback = () -> onAckTimeout(mySeq);
        ui.postDelayed(ackFallback, WRITE_ACK_TIMEOUT_MS);
    }

    /** onCharacteristicWrite → 主线程. */
    private void onWriteAck(int status) {
        if (pendingWrite == null) return;
        if (inFlightSeq == 0) {
            // 超时兜底已经放行过, 回调迟到了 → 忽略, 不能拿它去放行第二次
            Log.w(TAG, "收到迟到的写回调 (status=" + status + "), 忽略");
            return;
        }
        if (ackFallback != null) {
            ui.removeCallbacks(ackFallback);
            ackFallback = null;
        }
        int seq = inFlightSeq;
        inFlightSeq = 0;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failWrite("第 " + seq + " 片写入失败 (gattStatus=" + status + "), 已发 " + writeOffset + " 字节");
            return;
        }
        scheduleNextSlice();
    }

    private void onAckTimeout(int seq) {
        ackFallback = null;
        if (pendingWrite == null || inFlightSeq != seq) return;
        writeMissingAcks++;
        Log.w(TAG, "第 " + seq + " 片在 " + WRITE_ACK_TIMEOUT_MS + "ms 内没有 onCharacteristicWrite 回调"
                + " — 按已发出继续 (重发会让打印机收到重复字节)");
        inFlightSeq = 0;
        scheduleNextSlice();
    }

    private void finishWrite() {
        PluginCall c = pendingWrite;
        clearWriteState();
        if (c == null) return;
        JSObject ret = new JSObject();
        ret.put("sentBytes", writeOffset);
        ret.put("chunks", writeChunkCount);
        // 取证用: 协商到的 MTU / 实际片大小 / 有几片没等到回调。语义上新增键, 不动 sentBytes/chunks。
        ret.put("missingAcks", writeMissingAcks);
        ret.put("chunkSize", writePerWrite);
        ret.put("mtu", mtu);
        if (writeMissingAcks > 0) {
            Log.w(TAG, "发送完成但有 " + writeMissingAcks + "/" + writeChunkCount + " 片没等到写回调");
        }
        Log.i(TAG, "发送完成 sentBytes=" + writeOffset + " chunks=" + writeChunkCount);
        c.resolve(ret);
    }

    private void failWrite(String msg) {
        PluginCall c = pendingWrite;
        clearWriteState();
        if (c != null) c.reject(msg + " (已发 " + writeOffset + " 字节 / " + writeChunkCount + " 片)");
    }

    private void clearWriteState() {
        pendingWrite = null;
        writeBuf = null;
        writeScheduled = false;
        inFlightSeq = 0;
        if (ackFallback != null) {
            ui.removeCallbacks(ackFallback);
            ackFallback = null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // disconnect / status
    // ══════════════════════════════════════════════════════════════

    @PluginMethod
    public void disconnect(PluginCall call) {
        cancelMtuFallback();
        cancelConnectTimeout();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception e) {
                Log.w(TAG, "disconnect 异常", e);
            }
            gatt = null;
        }
        if (pendingWrite != null) failWrite("打印机已断开");
        if (pendingConnect != null) {
            PluginCall c = pendingConnect;
            pendingConnect = null;
            c.reject("连接已取消");
        }
        stopScanQuietly();
        if (pendingScan != null) {
            PluginCall c = pendingScan;
            pendingScan = null;
            c.reject("扫描已取消");
        }
        writeChar = null;
        mtu = DEFAULT_MTU;
        mtuRequested = false;
        servicesRequested = false;
        connectedMac = null;
        connectedName = null;
        JSObject ret = new JSObject();
        ret.put("connected", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void status(PluginCall call) {
        boolean connected = gatt != null && writeChar != null;
        String addr = connectedMac;
        String name = connectedName;
        if (gatt != null) {
            try {
                BluetoothDevice d = gatt.getDevice();
                if (d != null) {
                    if (addr == null) addr = d.getAddress();
                    if (name == null || name.isEmpty()) name = d.getName();
                }
            } catch (SecurityException ignored) { }
        }
        JSObject ret = new JSObject();
        ret.put("connected", connected);
        ret.put("mtu", mtu);
        ret.put("mac", addr == null ? JSONObject.NULL : addr);
        ret.put("name", name == null || name.isEmpty() ? JSONObject.NULL : name);
        call.resolve(ret);
    }

    @Override
    protected void handleOnDestroy() {
        stopScanQuietly();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) { }
            gatt = null;
        }
        super.handleOnDestroy();
    }
}
