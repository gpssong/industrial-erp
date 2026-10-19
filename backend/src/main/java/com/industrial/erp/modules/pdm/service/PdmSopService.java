package com.industrial.erp.modules.pdm.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.pdm.entity.MesProcessSop;
import com.industrial.erp.modules.pdm.mapper.MesProcessSopMapper;
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
 * v1.1.90 PDM — SOP (作业指导书) 管理.
 *
 * <p>核心规则: 同一工序同时刻最多 1 条 ACTIVE. 激活 SOP 时:
 * <ol>
 *   <li>把同工序下其他 ACTIVE / DRAFT 状态全部置 DEPRECATED (target 自己除外)</li>
 *   <li>把 target 置 ACTIVE</li>
 * </ol>
 *
 * <p>状态机: DRAFT (起草) → ACTIVE (生效, 同时刻唯一) → DEPRECATED (被新版本替换或取消).
 * 一旦 DEPRECATED 不可回滚 (历史快照, 留作追溯). DRAFT 可激活.
 *
 * <p>审计: 沿用 McpAuditService 教训 — <b>同步直写</b> SysOperLogMapper.insert.
 */
@Service
public class PdmSopService {

    private static final Logger log = LoggerFactory.getLogger(PdmSopService.class);

    private final MesProcessSopMapper sopMapper;
    private final SysOperLogMapper operLogMapper;

    public PdmSopService(MesProcessSopMapper sopMapper, SysOperLogMapper operLogMapper) {
        this.sopMapper = sopMapper;
        this.operLogMapper = operLogMapper;
    }

    /** 某工序的全部 SOP, 按生效日期倒序 (最新在前). */
    @Transactional(readOnly = true)
    public List<MesProcessSop> list(Long processDefId) {
        if (processDefId == null) return List.of();
        return sopMapper.selectByProcessDefId(processDefId);
    }

    /** 某工序当前生效的 SOP (status=ACTIVE, 同时刻唯一). */
    @Transactional(readOnly = true)
    public MesProcessSop getActive(Long processDefId) {
        if (processDefId == null) return null;
        return sopMapper.selectActiveByProcessDefId(processDefId);
    }

    /** 新增 / 更新草稿. status 入参可指定 (不传 = DRAFT). */
    @Transactional(rollbackFor = Exception.class)
    public void save(MesProcessSop sop) {
        if (sop.getProcessDefId() == null) {
            throw new IllegalArgumentException("processDefId 必填");
        }
        if (StrUtil.isBlank(sop.getVersion())) sop.setVersion("1.0");
        if (StrUtil.isBlank(sop.getStatus())) sop.setStatus(Constants.SOP_DRAFT);
        if (sop.getVersionLock() == null) sop.setVersionLock(0);

        if (sop.getId() == null) {
            sopMapper.insert(sop);
            log.info("[PDM] 新建 SOP id={} ver={} processDefId={} status={}",
                    sop.getId(), sop.getVersion(), sop.getProcessDefId(), sop.getStatus());
        } else {
            // 已激活的 SOP 改 content/version 触发 DEPRECATED 风险 → 直接禁止
            MesProcessSop exist = sopMapper.selectById(sop.getId());
            if (exist != null && Constants.SOP_ACTIVE.equals(exist.getStatus())
                    && !Constants.SOP_ACTIVE.equals(sop.getStatus())) {
                throw new IllegalArgumentException("已激活的 SOP 不能直接改状态, 请走 activate/新建流程");
            }
            sopMapper.updateById(sop);
            log.info("[PDM] 更新 SOP id={} ver={} status={}", sop.getId(), sop.getVersion(), sop.getStatus());
        }
        writeAudit("SAVE_SOP", 0,
                "processDefId=" + sop.getProcessDefId() + " ver=" + sop.getVersion()
                        + " status=" + sop.getStatus() + " id=" + sop.getId(), null);
    }

    /**
     * 激活指定 SOP: 同工序其他非 ACTIVE 状态 (DRAFT, 已存在的 DEPRECATED 跳过) 标 DEPRECATED,
     * target 标 ACTIVE.
     */
    @Transactional(rollbackFor = Exception.class)
    public void activate(Long id) {
        MesProcessSop sop = sopMapper.selectById(id);
        if (sop == null) {
            throw new IllegalArgumentException("SOP 不存在 id=" + id);
        }
        if (Constants.SOP_DEPRECATED.equals(sop.getStatus())) {
            throw new IllegalArgumentException("已废弃的 SOP 不能再激活, 请新建一条 SOP 再激活");
        }
        if (Constants.SOP_ACTIVE.equals(sop.getStatus())) {
            // 已经是 ACTIVE — 不做任何事, 但写一条审计留痕
            writeAudit("ACTIVATE_SOP_NOOP", 0, "sop id=" + id + " 已是 ACTIVE, 跳过", null);
            return;
        }

        // 1) 同工序下, 除 target 外, 把 ACTIVE / DRAFT 全部标 DEPRECATED
        List<MesProcessSop> all = sopMapper.selectByProcessDefId(sop.getProcessDefId());
        for (MesProcessSop x : all) {
            if (x.getId().equals(sop.getId())) continue;
            if (Constants.SOP_DEPRECATED.equals(x.getStatus())) continue;  // 历史的 DEPRECATED 不动
            MesProcessSop deprecate = new MesProcessSop();
            deprecate.setId(x.getId());
            deprecate.setStatus(Constants.SOP_DEPRECATED);
            sopMapper.updateById(deprecate);
            log.info("[PDM] 沉余 SOP id={} → DEPRECATED (因 {} 激活)", x.getId(), sop.getId());
        }

        // 2) target 标 ACTIVE
        MesProcessSop upd = new MesProcessSop();
        upd.setId(sop.getId());
        upd.setStatus(Constants.SOP_ACTIVE);
        sopMapper.updateById(upd);

        log.info("[PDM] 激活 SOP id={} ver={} processDefId={}", sop.getId(), sop.getVersion(), sop.getProcessDefId());
        writeAudit("ACTIVATE_SOP", 0,
                "processDefId=" + sop.getProcessDefId() + " ver=" + sop.getVersion() + " id=" + sop.getId(), null);
    }

    /** 软删 — 业务上仅 DRAFT 可删, ACTIVE / DEPRECATED 建议保留做历史. */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MesProcessSop exist = sopMapper.selectById(id);
        if (exist == null) return;
        if (Constants.SOP_ACTIVE.equals(exist.getStatus())) {
            throw new IllegalArgumentException("已生效的 SOP 不能直接删除, 请先新建一条替换 SOP 让其 DEPRECATED");
        }
        sopMapper.deleteById(id);
        writeAudit("DEL_SOP", 0, "ver=" + exist.getVersion() + " id=" + id, null);
        log.info("[PDM] 软删 SOP id={} ver={}", id, exist.getVersion());
    }

    // 审计 (同步直写)
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
