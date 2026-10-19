package com.industrial.erp.modules.em.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import com.industrial.erp.modules.em.entity.BaseEquipmentProcess;
import com.industrial.erp.modules.em.mapper.BaseEquipmentMapper;
import com.industrial.erp.modules.em.mapper.BaseEquipmentProcessMapper;
import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import com.industrial.erp.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.1.90 EM — 设备主数据 + 设备-工序 M2M 服务.
 *
 * <p>职责范围 (一期):
 * <ol>
 *   <li><b>设备主数据 CRUD</b> — {@link #page}/{@link #save}/{@link #delete}/{@link #findById}
 *       软删 + 乐观锁 + 审计</li>
 *   <li><b>按车间/类型筛选</b> — {@link #listByWorkshop} 供工作台看板用 (车间里能开哪些设备)</li>
 *   <li><b>设备-工序绑定</b> — {@link #bindProcesses} 一次提交整组: softDelete 旧绑定 → insert 新绑定。
 *       业务上"先全删后插"对前端简单 (用户保存时无需 diff), 一期范围内 OK。</li>
 *   <li><b>工序反向查询设备</b> — Mapper 暴露 {@code selectByProcessName}, 留作后续排程接口用</li>
 * </ol>
 *
 * <p>审计: 沿用 {@code McpAuditService} 教训 — <b>同步直写</b>
 * {@code SysOperLogMapper.insert} (module=设备管理, businessType=EM), 不走 @Async @EventListener。
 */
@Service
public class EquipmentService {

    private static final Logger log = LoggerFactory.getLogger(EquipmentService.class);

    private final BaseEquipmentMapper equipmentMapper;
    private final BaseEquipmentProcessMapper processMapper;
    private final SysOperLogMapper operLogMapper;

    public EquipmentService(BaseEquipmentMapper equipmentMapper,
                            BaseEquipmentProcessMapper processMapper,
                            SysOperLogMapper operLogMapper) {
        this.equipmentMapper = equipmentMapper;
        this.processMapper = processMapper;
        this.operLogMapper = operLogMapper;
    }

    // ==================================================================
    // 1. 设备主数据 CRUD
    // ==================================================================

    /**
     * 分页查询设备主数据. {@code workshop} / {@code type} 任一可空 (空时不参与 WHERE).
     */
    @Transactional(readOnly = true)
    public IPage<BaseEquipment> page(String workshop, String type, Integer pageNum, Integer pageSize) {
        Page<BaseEquipment> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BaseEquipment> w = new LambdaQueryWrapper<>();
        w.eq(BaseEquipment::getDeleted, 0);
        if (StrUtil.isNotBlank(workshop)) {
            w.eq(BaseEquipment::getWorkshop, workshop);
        }
        if (StrUtil.isNotBlank(type)) {
            w.eq(BaseEquipment::getEquipmentType, type);
        }
        w.orderByAsc(BaseEquipment::getEquipmentCode);
        return equipmentMapper.selectPage(p, w);
    }

    /**
     * 新增或更新设备主数据. {@code id=null} 走 insert, 否则 updateById.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseEquipment save(BaseEquipment equipment) {
        if (StrUtil.isBlank(equipment.getEquipmentCode())) {
            throw BizException.of("设备编码必填");
        }
        if (StrUtil.isBlank(equipment.getEquipmentName())) {
            throw BizException.of("设备名称必填");
        }
        if (StrUtil.isBlank(equipment.getEquipmentType())) {
            throw BizException.of("设备类型必填");
        }
        if (StrUtil.isBlank(equipment.getStatus())) {
            equipment.setStatus("ACTIVE");
        }
        if (equipment.getId() == null) {
            equipment.setCreateBy(SecurityContext.getUserId());
            equipmentMapper.insert(equipment);
            writeAudit("SAVE_EQUIPMENT", 1, "新增设备 " + equipment.getEquipmentCode()
                    + " (" + equipment.getEquipmentName() + ")", null);
        } else {
            // 更新: 不允许改 equipmentCode (设备编码是唯一业务标识)
            BaseEquipment origin = equipmentMapper.selectById(equipment.getId());
            if (origin == null) throw BizException.of("设备不存在");
            equipment.setEquipmentCode(origin.getEquipmentCode());
            equipment.setUpdateBy(SecurityContext.getUserId());
            equipmentMapper.updateById(equipment);
            writeAudit("UPDATE_EQUIPMENT", 1, "更新设备 " + equipment.getEquipmentCode()
                    + " (" + equipment.getEquipmentName() + ")", null);
        }
        log.info("[EM] 保存设备 code={} id={}", equipment.getEquipmentCode(), equipment.getId());
        return equipment;
    }

    /** 软删除设备 (MyBatis-Plus @TableLogic 自动写 deleted=1). */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BaseEquipment origin = equipmentMapper.selectById(id);
        if (origin == null) throw BizException.of("设备不存在");
        equipmentMapper.deleteById(id);
        // 同步 softDelete 该设备的所有工序绑定
        processMapper.update(null, new LambdaUpdateWrapper<BaseEquipmentProcess>()
                .eq(BaseEquipmentProcess::getEquipmentId, id)
                .set(BaseEquipmentProcess::getDeleted, 1)
                .set(BaseEquipmentProcess::getUpdateBy, SecurityContext.getUserId()));
        writeAudit("DELETE_EQUIPMENT", 1, "删除设备 id=" + id + " code=" + origin.getEquipmentCode(), null);
        log.info("[EM] 删除设备 id={} code={}", id, origin.getEquipmentCode());
    }

    @Transactional(readOnly = true)
    public BaseEquipment findById(Long id) {
        BaseEquipment e = equipmentMapper.selectById(id);
        if (e == null) throw BizException.of("设备不存在");
        return e;
    }

    /**
     * 按车间筛选 (供排程/看板下拉用). {@code workshop=null} 表示全车间.
     */
    @Transactional(readOnly = true)
    public List<BaseEquipment> listByWorkshop(String workshop) {
        return equipmentMapper.selectByWorkshopAndType(workshop, null);
    }

    // ==================================================================
    // 2. 设备-工序 M2M 绑定
    // ==================================================================

    /**
     * 一次性替换某设备的工序绑定列表 — 先 softDelete 所有 existing, 再 insert 新的.
     *
     * <p>业务决策: 一期不维护 history (谁把"分切"从设备 A 改到设备 B), 改用 softDelete
     * 保留审计痕迹足够. 工序绑定按 sort_no 升序展示.
     *
     * @param equipmentId  设备 id
     * @param processNames 工序名列表 (空列表 = 清空所有绑定)
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindProcesses(Long equipmentId, List<String> processNames) {
        BaseEquipment origin = equipmentMapper.selectById(equipmentId);
        if (origin == null) throw BizException.of("设备不存在");

        // softDelete 所有 existing
        processMapper.update(null, new LambdaUpdateWrapper<BaseEquipmentProcess>()
                .eq(BaseEquipmentProcess::getEquipmentId, equipmentId)
                .set(BaseEquipmentProcess::getDeleted, 1)
                .set(BaseEquipmentProcess::getUpdateBy, SecurityContext.getUserId()));

        // insert 新的
        if (processNames != null) {
            int sort = 0;
            Long uid = SecurityContext.getUserId();
            for (String name : processNames) {
                if (StrUtil.isBlank(name)) continue;
                BaseEquipmentProcess p = new BaseEquipmentProcess();
                p.setEquipmentId(equipmentId);
                p.setProcessName(name.trim());
                p.setSortNo(sort++);
                p.setCreateBy(uid);
                processMapper.insert(p);
            }
        }
        writeAudit("BIND_PROCESSES", 1, "设备 " + origin.getEquipmentCode()
                + " 绑定工序: " + (processNames == null ? "[]" : processNames.toString()), null);
        log.info("[EM] 设备 {} 绑工序: {} 项", origin.getEquipmentCode(),
                processNames == null ? 0 : processNames.size());
    }

    /** 查询某设备绑定的工序列表 (按 sort_no 排序). */
    @Transactional(readOnly = true)
    public List<BaseEquipmentProcess> listProcesses(Long equipmentId) {
        return processMapper.selectByEquipmentId(equipmentId);
    }

    // ==================================================================
    // 审计 (同步直写, McpAuditService 教训)
    // ==================================================================

    private void writeAudit(String method, int status, String what, String errMsg) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("设备管理");
            l.setBusinessType("EM");
            l.setMethod("[EM] " + method);
            l.setRequestMethod("POST");
            l.setRequestParam(what);
            if (errMsg != null) l.setErrorMsg(StrUtil.maxLength(errMsg, 1000));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(status);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
            log.info("[EM] audit ok method={} status={}", method, status);
        } catch (Exception e) {
            log.warn("[EM] 审计写入失败 (不阻塞主流程): {}", e.getMessage());
        }
    }
}