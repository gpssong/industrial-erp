package com.industrial.erp.modules.pdm.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.modules.pdm.entity.MesProcessParam;
import com.industrial.erp.modules.pdm.mapper.MesProcessParamMapper;
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
 * v1.1.90 PDM — 工艺参数 CRUD.
 *
 * <p>工艺参数 = 工序的「温度/压力/速度/时间」等关键控制点 (含上下限).
 * 一对多: 一个工序定义 (mes_process_def) 配 N 条参数.
 *
 * <p>审计: 沿用 McpAuditService 教训 —— <b>同步直写</b> SysOperLogMapper.insert,
 * 不用 @Async @EventListener.
 */
@Service
public class PdmProcessParamService {

    private static final Logger log = LoggerFactory.getLogger(PdmProcessParamService.class);

    private final MesProcessParamMapper paramMapper;
    private final SysOperLogMapper operLogMapper;

    public PdmProcessParamService(MesProcessParamMapper paramMapper, SysOperLogMapper operLogMapper) {
        this.paramMapper = paramMapper;
        this.operLogMapper = operLogMapper;
    }

    /** 某工序下的全部参数 (按 sort_no 升序). */
    @Transactional(readOnly = true)
    public List<MesProcessParam> list(Long processDefId) {
        if (processDefId == null) return List.of();
        return paramMapper.selectByProcessDefId(processDefId);
    }

    /** 新增 / 更新 — 由 BaseEntity FieldFill 自动写 createBy/updateBy/createTime/updateTime. */
    @Transactional(rollbackFor = Exception.class)
    public void save(MesProcessParam p) {
        if (p.getProcessDefId() == null) {
            throw new IllegalArgumentException("processDefId 必填");
        }
        if (StrUtil.isBlank(p.getParamKey())) {
            throw new IllegalArgumentException("参数名 paramKey 必填");
        }
        if (p.getIsCritical() == null) p.setIsCritical(0);
        if (p.getSortNo() == null) p.setSortNo(0);

        if (p.getId() == null) {
            paramMapper.insert(p);
            log.info("[PDM] 新增工艺参数 id={} key={} processDefId={}", p.getId(), p.getParamKey(), p.getProcessDefId());
        } else {
            paramMapper.updateById(p);
            log.info("[PDM] 更新工艺参数 id={} key={}", p.getId(), p.getParamKey());
        }
        writeAudit("SAVE_PARAM", 0,
                "processDefId=" + p.getProcessDefId() + " key=" + p.getParamKey() + " id=" + p.getId(), null);
    }

    /** 软删 (BaseMapper.deleteById 走 @TableLogic 自动 UPDATE deleted=1). */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MesProcessParam exist = paramMapper.selectById(id);
        if (exist != null) {
            paramMapper.deleteById(id);
            writeAudit("DEL_PARAM", 0, "key=" + exist.getParamKey() + " id=" + id, null);
            log.info("[PDM] 软删工艺参数 id={} key={}", id, exist.getParamKey());
        }
    }

    // 审计 (同步直写, McpAuditService 教训)
    private void writeAudit(String method, int status, String what, String errMsg) {
        try {
            SysOperLog l = new SysOperLog();
            l.setModule("工艺参数");
            l.setBusinessType("PDM");
            l.setMethod("[PDM] " + method);
            l.setRequestMethod("POST");
            l.setRequestParam(what);
            if (errMsg != null) l.setErrorMsg(StrUtil.maxLength(errMsg, 1000));
            l.setUserId(SecurityContext.getUserId());
            l.setUsername(SecurityContext.getUsername());
            l.setStatus(status);
            l.setOperTime(LocalDateTime.now());
            operLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[PDM] 审计写入失败 (不阻塞主流程): {}", e.getMessage());
        }
    }
}
