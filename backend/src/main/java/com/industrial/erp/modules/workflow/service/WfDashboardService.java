package com.industrial.erp.modules.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.workflow.entity.WfEmergencyPlan;
import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import com.industrial.erp.modules.workflow.entity.WfSafetyRecord;
import com.industrial.erp.modules.workflow.mapper.WfEmergencyPlanMapper;
import com.industrial.erp.modules.workflow.mapper.WfMaintainRecordMapper;
import com.industrial.erp.modules.workflow.mapper.WfSafetyRecordMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.79 hotfix: 工作流看板 — next_due_date / next_drill_date 聚合查询.
 *
 * <p>返回三类记录 (维保 / 检查 / 预案) 的 next_due_date (或 next_drill_date),
 * 按 dueDate ASC, 取 days 天内到期的. 供首页「工作流看板」卡片渲染,
 * 让管理员一眼看到"最近 30 天内哪些维保/检查/演练到期".
 */
@Service
public class WfDashboardService {

    public WfDashboardService(WfMaintainRecordMapper maintainMapper,
                              WfSafetyRecordMapper safetyMapper,
                              WfEmergencyPlanMapper emergencyMapper,
                              PermissionService permService) {
        this.maintainMapper = maintainMapper;
        this.safetyMapper = safetyMapper;
        this.emergencyMapper = emergencyMapper;
        this.permService = permService;
    }

    private final WfMaintainRecordMapper maintainMapper;
    private final WfSafetyRecordMapper safetyMapper;
    private final WfEmergencyPlanMapper emergencyMapper;
    private final PermissionService permService;

    /**
     * 聚合 3 类记录的 due-date, 限 days 天内到期的.
     * 每条返回 Map: { type, id, no, label, dueDate, daysLeft, status }.
     * type: MAINTAIN / SAFETY / EMERGENCY.
     */
    public List<Map<String, Object>> upcoming(int days) {
        permService.requirePerm("work:maintain:list");
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(Math.max(days, 1));
        List<Map<String, Object>> out = new ArrayList<>();

        // 1) 维保记录
        LambdaQueryWrapper<WfMaintainRecord> mw = new LambdaQueryWrapper<WfMaintainRecord>()
                .ge(WfMaintainRecord::getNextDueDate, today)
                .le(WfMaintainRecord::getNextDueDate, end)
                .orderByAsc(WfMaintainRecord::getNextDueDate)
                .last("LIMIT 100");
        for (WfMaintainRecord r : maintainMapper.selectList(mw)) {
            out.add(item("MAINTAIN", r.getId(), r.getRecordNo(),
                    r.getDeviceName(), r.getNextDueDate(), today, r.getBillStatus()));
        }

        // 2) 安全检查
        LambdaQueryWrapper<WfSafetyRecord> sw = new LambdaQueryWrapper<WfSafetyRecord>()
                .ge(WfSafetyRecord::getNextDueDate, today)
                .le(WfSafetyRecord::getNextDueDate, end)
                .orderByAsc(WfSafetyRecord::getNextDueDate)
                .last("LIMIT 100");
        for (WfSafetyRecord r : safetyMapper.selectList(sw)) {
            out.add(item("SAFETY", r.getId(), r.getRecordNo(),
                    r.getSite() == null ? "" : r.getSite(), r.getNextDueDate(), today, r.getBillStatus()));
        }

        // 3) 应急预案 (next_drill_date)
        LambdaQueryWrapper<WfEmergencyPlan> ew = new LambdaQueryWrapper<WfEmergencyPlan>()
                .ge(WfEmergencyPlan::getNextDrillDate, today)
                .le(WfEmergencyPlan::getNextDrillDate, end)
                .orderByAsc(WfEmergencyPlan::getNextDrillDate)
                .last("LIMIT 100");
        for (WfEmergencyPlan r : emergencyMapper.selectList(ew)) {
            String label = (r.getPlanNo() == null ? "" : r.getPlanNo())
                    + (r.getScenario() == null ? "" : " · " + r.getScenario());
            out.add(item("EMERGENCY", r.getId(), r.getPlanNo(), label, r.getNextDrillDate(), today, r.getBillStatus()));
        }

        return out;
    }

    private Map<String, Object> item(String type, Long id, String no, String label,
                                     LocalDate due, LocalDate today, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("no", no);
        m.put("label", label);
        m.put("dueDate", due);
        m.put("daysLeft", java.time.temporal.ChronoUnit.DAYS.between(today, due));
        m.put("status", status);
        return m;
    }
}
