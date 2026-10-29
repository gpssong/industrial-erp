package com.industrial.erp.modules.mes.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.PrdOrderNameInjector;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.mes.entity.MesWeightChannel;
import com.industrial.erp.modules.mes.entity.MesWeightDevice;
import com.industrial.erp.modules.mes.entity.MesWeightSample;
import com.industrial.erp.modules.mes.mapper.MesWeightChannelMapper;
import com.industrial.erp.modules.mes.mapper.MesWeightDeviceMapper;
import com.industrial.erp.modules.mes.mapper.MesWeightSampleMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * v1.1.119 MES — 吹膜机米克重采集服务.
 *
 * <p>两条写入路径:
 * <ul>
 *   <li>{@link #reportSamples} —— ESP32 设备上报, 免登录态, 走
 *       {@code /device/weight/report}。设备侧时钟做 {@link #collectTime}。</li>
 *   <li>其余方法 —— PC 端带登录态的查询与配置维护。</li>
 * </ul>
 *
 * <p><b>审计字段陷阱</b>: {@code MybatisPlusConfig.metaObjectHandler} 里
 * {@code SecurityContext.getUserId()} 在无登录态时返回 null, 整个
 * {@code if (userId != null)} 分支不成立 → 设备写入的数据 {@code createBy}
 * 恒 NULL。这里<b>不改全局 handler</b> (会波及全库 60+ 表), 而是在
 * {@link #reportSamples} 里显式 {@code setCreateBy(DEVICE_OPERATOR_ID)},
 * 靠 {@code strictInsertFill} "仅填 null 字段" 的语义原样落库。
 */
@Service
public class MesWeightService {

    private static final Logger log = LoggerFactory.getLogger(MesWeightService.class);

    /** 设备写入的审计操作人: 系统内置的"设备采集"虚拟账号 id, 需先在 sys_user 建档 */
    public static final Long DEVICE_OPERATOR_ID = 999L;

    /** 曲线单次最大返回条数 (前端传更小值也以此为上限, 防拉爆) */
    private static final int CURVE_MAX_LIMIT = 2000;

    private final MesWeightDeviceMapper deviceMapper;
    private final MesWeightChannelMapper channelMapper;
    private final MesWeightSampleMapper sampleMapper;
    private final SysOperLogMapper operLogMapper;
    private final PrdOrderMapper prdOrderMapper;

    public MesWeightService(MesWeightDeviceMapper deviceMapper,
                            MesWeightChannelMapper channelMapper,
                            MesWeightSampleMapper sampleMapper,
                            SysOperLogMapper operLogMapper,
                            PrdOrderMapper prdOrderMapper) {
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.sampleMapper = sampleMapper;
        this.operLogMapper = operLogMapper;
        this.prdOrderMapper = prdOrderMapper;
    }

    // ==================================================================
    //  设备上报 (免登录态, ESP32 → ERP)
    // ==================================================================

    /**
     * ESP32 上报采样. 一次可带多通道.
     *
     * @param deviceCode 设备编码 (对应 mes_weight_device.device_code)
     * @param items      各通道采样
     * @param fwVersion  固件版本 (可空)
     * @param clientIp   上报来源 IP (由 Controller 传入)
     * @return 本次入库条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int reportSamples(String deviceCode,
                             List<MesWeightSample> items,
                             String fwVersion,
                             String clientIp) {
        if (StrUtil.isBlank(deviceCode)) {
            throw new BizException("deviceCode 不能为空");
        }
        if (items == null || items.isEmpty()) {
            return 0;
        }

        MesWeightDevice device = deviceMapper.selectOne(
                Wrappers.<MesWeightDevice>lambdaQuery()
                        .eq(MesWeightDevice::getDeviceCode, deviceCode)
                        .last("LIMIT 1"));
        if (device == null) {
            // 采集器未注册 = 配置问题, 直接拒收, 否则会静默产生孤儿数据
            throw new BizException("采集器未注册: " + deviceCode);
        }

        // 通道配置缓存 (一次查询, 供下面算偏差/超标)
        List<MesWeightChannel> channels = channelMapper.selectList(
                Wrappers.<MesWeightChannel>lambdaQuery()
                        .eq(MesWeightChannel::getDeviceId, device.getId()));
        Map<Integer, MesWeightChannel> chMap = new java.util.HashMap<>();
        for (MesWeightChannel c : channels) {
            chMap.put(c.getChannelNo(), c);
        }

        LocalDateTime now = LocalDateTime.now();
        for (MesWeightSample s : items) {
            // 显式赋审计人 — strictInsertFill 只填 null 字段, 这里预赋值故不会被覆盖
            s.setCreateBy(DEVICE_OPERATOR_ID);
            s.setDeviceId(device.getId());
            s.setDeviceCode(deviceCode);
            if (s.getEquipmentId() == null) {
                s.setEquipmentId(device.getEquipmentId());
            }
            if (StrUtil.isBlank(s.getEquipmentCode())) {
                s.setEquipmentCode(device.getEquipmentCode());
            }
            if (s.getChannelNo() == null) s.setChannelNo(1);
            if (s.getCollectTime() == null) s.setCollectTime(now);

            // 偏差 / 超标: 设定值优先取通道配置 (工艺口径), 否则用采样携带的冗余值
            MesWeightChannel ch = chMap.get(s.getChannelNo());
            BigDecimal set = (ch != null && ch.getSetWeight() != null)
                    ? ch.getSetWeight() : s.getSetWeight();
            s.setSetWeight(set);
            applyDeviation(s, set, ch);

            sampleMapper.insert(s);
        }

        // 采集器在线状态刷新
        device.setOnlineStatus("ONLINE");
        device.setLastReportTime(now);
        device.setLastReportIp(clientIp);
        if (StrUtil.isNotBlank(fwVersion)) device.setFwVersion(fwVersion);
        deviceMapper.updateById(device);

        writeAudit("DEVICE_REPORT", 1,
                "采集器 " + deviceCode + " 上报 " + items.size() + " 条采样", null);
        log.info("[MES-Weight] 采集器 {} 上报 {} 条, 设备={}", deviceCode, items.size(), device.getId());
        return items.size();
    }

    /** 算偏差 / 偏差百分比 / 超标标记。set 为 null 时不算偏差。 */
    private void applyDeviation(MesWeightSample s, BigDecimal set, MesWeightChannel ch) {
        BigDecimal actual = s.getActualWeight();
        if (actual == null || set == null) {
            return;
        }
        BigDecimal dev = actual.subtract(set).setScale(3, RoundingMode.HALF_UP);
        s.setDeviation(dev);
        if (set.compareTo(BigDecimal.ZERO) != 0) {
            s.setDeviationPct(
                    dev.multiply(BigDecimal.valueOf(100))
                       .divide(set.abs(), 3, RoundingMode.HALF_UP));
        }
        // 超标判定用通道公差; 未配公差则不判 (避免无公差时全算超标)
        if (ch != null && (ch.getUpperTol() != null || ch.getLowerTol() != null)) {
            BigDecimal upper = set.add(ch.getUpperTol() == null ? BigDecimal.ZERO : ch.getUpperTol());
            BigDecimal lower = set.subtract(ch.getLowerTol() == null ? BigDecimal.ZERO : ch.getLowerTol());
            s.setIsOver(actual.compareTo(upper) > 0 || actual.compareTo(lower) < 0 ? 1 : 0);
        } else {
            s.setIsOver(0);
        }
    }

    // ==================================================================
    //  业务查询 (PC 端, 带登录态)
    // ==================================================================

    /** 采集器列表 (分页) */
    @Transactional(readOnly = true)
    public IPage<MesWeightDevice> pageDevices(Integer pageNum, Integer pageSize,
                                             String workshop, String keyword) {
        Page<MesWeightDevice> p = new Page<>(pageNum, pageSize);
        return deviceMapper.selectPage(p, Wrappers.<MesWeightDevice>lambdaQuery()
                .eq(StrUtil.isNotBlank(workshop), MesWeightDevice::getWorkshop, workshop)
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(MesWeightDevice::getDeviceCode, keyword)
                        .or().like(MesWeightDevice::getDeviceName, keyword)
                        .or().like(MesWeightDevice::getEquipmentName, keyword))
                .orderByDesc(MesWeightDevice::getId));
    }

    /** 某采集器的通道配置 */
    @Transactional(readOnly = true)
    public List<MesWeightChannel> listChannels(Long deviceId) {
        return channelMapper.selectList(Wrappers.<MesWeightChannel>lambdaQuery()
                .eq(MesWeightChannel::getDeviceId, deviceId)
                .orderByAsc(MesWeightChannel::getChannelNo));
    }

    /** 保存通道配置 (id 有则改, 无则新) */
    @Transactional(rollbackFor = Exception.class)
    public MesWeightChannel saveChannel(MesWeightChannel ch) {
        if (ch.getDeviceId() == null) {
            throw new BizException("deviceId 不能为空");
        }
        if (ch.getChannelNo() == null) {
            throw new BizException("通道号不能为空");
        }
        if (ch.getRegType() == null) ch.setRegType(4);
        if (ch.getScale() == null) ch.setScale(BigDecimal.ONE);
        if (ch.getUnit() == null) ch.setUnit("g");

        if (ch.getId() == null) {
            channelMapper.insert(ch);
        } else {
            channelMapper.updateById(ch);
        }
        writeAudit("SAVE_CHANNEL", 1, "通道配置 " + ch.getChannelNo(), null);
        return ch;
    }

    /**
     * 曲线数据. 返回按时间<b>正序</b> (ECharts x 轴从左到右).
     *
     * <p>SQL 里倒序取 limit 条再翻转 —— 直接正序 + limit 会取到<b>最早</b>的 N 条,
     * 实时曲线会永远停在开机那一刻, 是个很容易踩的坑。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> curve(Long deviceId, Integer channelNo,
                                           LocalDateTime from, LocalDateTime to, int limit) {
        if (deviceId == null) {
            throw new BizException("deviceId 不能为空");
        }
        int lim = Math.min(limit <= 0 ? 500 : limit, CURVE_MAX_LIMIT);
        List<Map<String, Object>> rows =
                sampleMapper.selectCurve(deviceId, channelNo, from, to, lim);
        Collections.reverse(rows);
        return rows;
    }

    /** 时间窗统计 (每通道均值/极值/超标率) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stats(Long deviceId, LocalDateTime from, LocalDateTime to) {
        if (deviceId == null) {
            throw new BizException("deviceId 不能为空");
        }
        return sampleMapper.selectStats(deviceId, from, to);
    }

    /** 采样明细分页 (设备端 list 口径, 带设备快照字段) */
    @Transactional(readOnly = true)
    public IPage<MesWeightSample> pageSamples(Integer pageNum, Integer pageSize,
                                              Long deviceId, Integer channelNo,
                                              LocalDateTime from, LocalDateTime to,
                                              Integer isOver) {
        Page<MesWeightSample> p = new Page<>(pageNum, pageSize);
        IPage<MesWeightSample> result = sampleMapper.selectPage(p, Wrappers.<MesWeightSample>lambdaQuery()
                .eq(deviceId != null, MesWeightSample::getDeviceId, deviceId)
                .eq(channelNo != null, MesWeightSample::getChannelNo, channelNo)
                .eq(isOver != null, MesWeightSample::getIsOver, isOver)
                .ge(from != null, MesWeightSample::getCollectTime, from)
                .le(to != null, MesWeightSample::getCollectTime, to)
                .orderByDesc(MesWeightSample::getCollectTime)
                .orderByAsc(MesWeightSample::getChannelNo));
        // v1.1.131: 注入关联工单的商品名称 (前端显示「工单号 · 品名」)
        PrdOrderNameInjector.inject(prdOrderMapper, result.getRecords(),
                MesWeightSample::getPrdOrderId, MesWeightSample::setProductName);
        return result;
    }

    // ==================================================================
    //  审计 (同步直写, McpAuditService 教训: @Async @EventListener 会漏日志)
    // ==================================================================

    private void writeAudit(String method, int status, String what, String errMsg) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("MES 米克重采集");
            l.setBusinessType("MES");
            l.setMethod("[MES-WEIGHT] " + method);
            l.setRequestMethod("POST");
            l.setRequestParam(what);
            if (errMsg != null) l.setErrorMsg(StrUtil.maxLength(errMsg, 1000));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(status);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[MES-Weight] 审计写入失败 (不阻塞主流程): {}", e.getMessage());
        }
    }
}