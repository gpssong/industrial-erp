package com.industrial.erp.modules.mes.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.mes.entity.MesProcessTime;
import com.industrial.erp.modules.mes.entity.MesSchedule;
import com.industrial.erp.modules.mes.mapper.MesProcessTimeMapper;
import com.industrial.erp.modules.mes.mapper.MesScheduleMapper;
import com.industrial.erp.modules.em.mapper.BaseEquipmentProcessMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.99 C4 — 生产排程 + 设备 OEE.
 *
 * <p>职责:
 * <ol>
 *   <li><b>排程 CRUD</b> — 把「工单×工序 派到 设备×时段」显式建模 (mes_schedule), 支撑车间派工甘特.</li>
 *   <li><b>冲突检测</b> — 同设备同一时段重叠 (排除 CANCELLED/本行) 时给出提示, 不硬拦 (排产是建议).</li>
 *   <li><b>设备承接校验</b> — 该设备绑定的工序 (base_equipment_process) 需包含排程工序名 (可选, 提示非阻断).</li>
 *   <li><b>OEE</b> — 按设备聚合 mes_process_time: 稼动率 × 性能率 × 良品率 (简化: 实际工时/计划工时 × 良品率).</li>
 * </ol>
 *
 * <p>审计: 排程维护走 {@code @OperLog} 由 controller 层标注; 本 service 只负责数据与算法.
 */
@Service
public class MesScheduleService {

    private static final Logger log = LoggerFactory.getLogger(MesScheduleService.class);

    private final MesScheduleMapper scheduleMapper;
    private final MesProcessTimeMapper processTimeMapper;
    private final BaseEquipmentProcessMapper equipmentProcessMapper;
    private final PermissionService permService;

    public MesScheduleService(MesScheduleMapper scheduleMapper, MesProcessTimeMapper processTimeMapper,
                              BaseEquipmentProcessMapper equipmentProcessMapper, PermissionService permService) {
        this.scheduleMapper = scheduleMapper;
        this.processTimeMapper = processTimeMapper;
        this.equipmentProcessMapper = equipmentProcessMapper;
        this.permService = permService;
    }

    // ==================================================================
    // 1. 排程分页 / 详情
    // ==================================================================

    public IPage<MesSchedule> page(Integer pageNum, Integer pageSize, Long equipmentId, Long prdOrderId, String status) {
        permService.requirePerm("production:schedule:list");
        Page<MesSchedule> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<MesSchedule> w = new LambdaQueryWrapper<>();
        w.eq(equipmentId != null, MesSchedule::getEquipmentId, equipmentId);
        w.eq(prdOrderId != null, MesSchedule::getPrdOrderId, prdOrderId);
        w.eq(StrUtil.isNotBlank(status), MesSchedule::getStatus, status);
        w.orderByAsc(MesSchedule::getPlanStart);
        return scheduleMapper.selectPage(p, w);
    }

    /** 某设备按时间轴排程 (甘特数据源), 仅活跃排程. */
    public List<MesSchedule> ganttByEquipment(Long equipmentId) {
        permService.requirePerm("production:schedule:list");
        return scheduleMapper.selectList(new LambdaQueryWrapper<MesSchedule>()
                .eq(MesSchedule::getEquipmentId, equipmentId)
                .ne(MesSchedule::getStatus, "CANCELLED")
                .orderByAsc(MesSchedule::getPlanStart));
    }

    // ==================================================================
    // 2. 排程新增 / 更新 (含冲突检测 + 设备承接提示)
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Long save(MesSchedule s) {
        permService.requirePerm("production:schedule:edit");
        if (s.getEquipmentId() == null) throw BizException.of("设备必填");
        if (s.getPlanStart() == null || s.getPlanEnd() == null) throw BizException.of("计划时段必填");
        if (s.getPlanEnd().isBefore(s.getPlanStart())) throw BizException.of("计划完工不能早于开工");
        if (StrUtil.isBlank(s.getStatus())) s.setStatus("PLANNED");
        s.setCreateBy(SecurityContext.getUserId());

        // 冲突检测 (同设备同时段, 排除 CANCELLED + 本行)
        List<MesSchedule> conflict = scheduleMapper.selectList(new LambdaQueryWrapper<MesSchedule>()
                .eq(MesSchedule::getEquipmentId, s.getEquipmentId())
                .ne("CANCELLED".equals(s.getStatus()), MesSchedule::getStatus, "CANCELLED")
                .lt(MesSchedule::getPlanStart, s.getPlanEnd())
                .gt(MesSchedule::getPlanEnd, s.getPlanStart())
                .ne(s.getId() != null, MesSchedule::getId, s.getId())
                .last("LIMIT 1"));
        if (!conflict.isEmpty()) {
            MesSchedule c = conflict.get(0);
            log.warn("[MES] 排程冲突设备={} 时段={}~{} 重叠已有 {} ({}), 仅提示不阻断",
                    s.getEquipmentId(), s.getPlanStart(), s.getPlanEnd(), c.getId(), c.getPrdOrderNo());
        }

        if (s.getId() == null) {
            scheduleMapper.insert(s);
        } else {
            s.setUpdateBy(SecurityContext.getUserId());
            scheduleMapper.updateById(s);
        }
        log.info("[MES] 保存排程 id={} 设备={} 工序={} 时段={}~{}",
                s.getId(), s.getEquipmentId(), s.getProcessName(), s.getPlanStart(), s.getPlanEnd());
        return s.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        permService.requirePerm("production:schedule:edit");
        MesSchedule s = scheduleMapper.selectById(id);
        if (s == null) throw BizException.of("排程不存在");
        MesSchedule u = new MesSchedule();
        u.setId(id);
        u.setStatus("CANCELLED");
        u.setUpdateBy(SecurityContext.getUserId());
        scheduleMapper.updateById(u);
    }

    /** 该设备绑定是否包含某工序名 (承接提示). 设备未绑任何工序 → 返回 true (不限制). */
    public boolean equipmentCoversProcess(Long equipmentId, String processName) {
        if (equipmentId == null || StrUtil.isBlank(processName)) return true;
        List<com.industrial.erp.modules.em.entity.BaseEquipmentProcess> bound =
                equipmentProcessMapper.selectByEquipmentId(equipmentId);
        if (bound == null || bound.isEmpty()) return true;
        for (com.industrial.erp.modules.em.entity.BaseEquipmentProcess b : bound) {
            if (processName.equals(b.getProcessName())) return true;
        }
        return false;
    }

    // ==================================================================
    // 3. OEE 聚合 (mes_process_time → 设备维度)
    // ==================================================================

    /** OEE 结果: 可用时间(计划) / 实际稼动时间 / 停机时间 / OEE(%) / 良品率(%). */
    public static class OeeResult {
        public Long equipmentId;
        public String equipmentCode;
        public long planMinutes = 0;      // 该设备计划总工时 (分钟)
        public long activeMinutes = 0;    // 实际稼动时间
        public long downtimeMinutes = 0;  // 停机时间
        public double oee = 0;           // OEE = (active/plan) × (1 - downtime/plan) × 良品率(简化取 100% 若无报工良率)
        public double availability = 0;  // 稼动率
        public double performance = 100; // 性能率 (简化)
        public double quality = 100;     // 良品率
        public int records = 0;
    }

    @Transactional(readOnly = true)
    public OeeResult oee(Long equipmentId) {
        permService.requirePerm("production:schedule:list");
        OeeResult r = new OeeResult();
        r.equipmentId = equipmentId;
        if (equipmentId == null) return r;

        // 实际稼动 + 停机 来自 mes_process_time
        List<MesProcessTime> times = processTimeMapper.selectList(new LambdaQueryWrapper<MesProcessTime>()
                .eq(MesProcessTime::getEquipmentId, equipmentId));
        r.records = times == null ? 0 : times.size();
        long activeMin = 0, downMin = 0;
        if (times != null) {
            for (MesProcessTime t : times) {
                if (t.getStartTime() != null && t.getEndTime() != null) {
                    activeMin += Math.max(0, Duration.between(t.getStartTime(), t.getEndTime()).toMinutes());
                }
                if (t.getDowntime() != null) downMin += t.getDowntime().longValue();
            }
        }
        // 计划工时 来自该设备未取消排程的 (plan_end - plan_start)
        List<MesSchedule> sched = scheduleMapper.selectList(new LambdaQueryWrapper<MesSchedule>()
                .eq(MesSchedule::getEquipmentId, equipmentId)
                .ne(MesSchedule::getStatus, "CANCELLED"));
        long planMin = 0;
        for (MesSchedule s : sched) {
            if (s.getPlanStart() != null && s.getPlanEnd() != null) {
                planMin += Math.max(0, Duration.between(s.getPlanStart(), s.getPlanEnd()).toMinutes());
            }
        }
        r.planMinutes = planMin;
        r.activeMinutes = activeMin;
        r.downtimeMinutes = downMin;
        if (planMin > 0) {
            double avail = Math.min(100.0, activeMin * 100.0 / planMin);
            // OEE = 稼动率 × 性能率 × 良品率; 无报工良率数据时 性能/良品 各取 100% (简化, 后续接 prd_process 良率可细化)
            r.availability = round2(avail);
            r.performance = 100.0;
            r.quality = 100.0;
            r.oee = round2(avail * 1.0 * 1.0);
        } else if (activeMin > 0) {
            r.availability = 100.0;
            r.oee = 100.0;
        }
        return r;
    }

    private static double round2(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue(); }

    /** 记录一段工序实际耗时 (报工/设备触发 OEE 数据源). 返回新行 id. */
    @Transactional(rollbackFor = Exception.class)
    public Long recordProcessTime(MesProcessTime t) {
        permService.requirePerm("production:schedule:list");
        t.setCreateBy(SecurityContext.getUserId());
        processTimeMapper.insert(t);
        return t.getId();
    }

    /** 某设备的耗时流水 (OEE 明细). */
    public List<MesProcessTime> processTimeByEquipment(Long equipmentId) {
        permService.requirePerm("production:schedule:list");
        return processTimeMapper.selectList(new LambdaQueryWrapper<MesProcessTime>()
                .eq(MesProcessTime::getEquipmentId, equipmentId)
                .orderByDesc(MesProcessTime::getStartTime));
    }

    /** 供 controller 聚合多设备 OEE (dashboard). */
    public Map<Long, OeeResult> oeeByEquipment(List<Long> equipmentIds) {
        Map<Long, OeeResult> m = new HashMap<>();
        if (equipmentIds != null) for (Long id : equipmentIds) m.put(id, oee(id));
        return m;
    }
}
