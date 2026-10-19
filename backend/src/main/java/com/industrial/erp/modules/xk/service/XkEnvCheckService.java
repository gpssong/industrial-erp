package com.industrial.erp.modules.xk.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.xk.entity.XkEnvCheck;
import com.industrial.erp.modules.xk.mapper.XkEnvCheckMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * v1.1.97 XK — 生产环境安全/卫生检查服务.
 *
 * <p>对应 Safety.vue Tab 3「生产环境检查」. 状态机:
 * <ul>
 *   <li>DRAFT — 已记录待办, 未整改完成 (新建后默认)</li>
 *   <li>CHECKED — 已完成, 整改归档 (提交 /check 后)</li>
 * </ul>
 *
 * <p>数据流: 新建 (DRAFT) -> /check 提交整改 (level/owner/deadline/desc + photos) -> CHECKED.
 * 完成后调用 {@link XkAuditService#writeAudit} 同步直写 xk_audit_log
 * (CLAUDE.md v1.1.79 hotfix-1 教训, 非 @Async).
 */
@Service
public class XkEnvCheckService {

    private static final Logger log = LoggerFactory.getLogger(XkEnvCheckService.class);

    private final XkEnvCheckMapper mapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final XkAuditService auditService;

    public XkEnvCheckService(XkEnvCheckMapper mapper,
                             BillNoGenerator billNoGenerator,
                             PermissionService permService,
                             XkAuditService auditService) {
        this.mapper = mapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public IPage<XkEnvCheck> page(String workshop, String envType, String status,
                                  Integer pageNum, Integer pageSize) {
        permService.requirePerm("xk:env:list");
        Page<XkEnvCheck> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<XkEnvCheck> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(workshop)) w.eq(XkEnvCheck::getWorkshop, workshop);
        if (StrUtil.isNotBlank(envType)) w.eq(XkEnvCheck::getEnvType, envType);
        if (StrUtil.isNotBlank(status)) w.eq(XkEnvCheck::getStatus, status);
        w.orderByDesc(XkEnvCheck::getPlannedAt);
        return mapper.selectPage(p, w);
    }

    @Transactional(readOnly = true)
    public XkEnvCheck findById(Long id) {
        permService.requirePerm("xk:env:list");
        return mapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public XkEnvCheck save(XkEnvCheck env) {
        if (env.getId() == null) {
            permService.requirePerm("xk:env:add");
            if (StrUtil.isBlank(env.getWorkshop())) throw BizException.of("车间必填");
            if (StrUtil.isBlank(env.getEnvType())) throw BizException.of("检查类型必填");
            if (StrUtil.isBlank(env.getCheckTitle())) throw BizException.of("检查主题必填");
            if (StrUtil.isBlank(env.getCheckNo())) env.setCheckNo(billNoGenerator.generate("EC"));
            if (StrUtil.isBlank(env.getStatus())) env.setStatus(Constants.STATUS_DRAFT);
            mapper.insert(env);
            log.info("[XK] 新建环境检查 checkNo={} workshop={} type={}",
                    env.getCheckNo(), env.getWorkshop(), env.getEnvType());
        } else {
            permService.requirePerm("xk:env:edit");
            XkEnvCheck origin = mapper.selectById(env.getId());
            if (origin == null) throw BizException.of("检查单不存在");
            if (Constants.STATUS_CHECKED.equals(origin.getStatus())) {
                throw BizException.of("已整改归档的检查单不可编辑");
            }
            env.setCheckNo(origin.getCheckNo());
            env.setStatus(origin.getStatus());
            mapper.updateById(env);
            log.info("[XK] 更新环境检查 checkNo={}", env.getCheckNo());
        }
        return env;
    }

    /**
     * 提交整改 -> 翻 CHECKED. 联动写 xk_audit_log (CLAUDE.md v1.1.79 hotfix-1 教训).
     *
     * <p>v1.1.98 X6: 等级 ≥ MEDIUM 时, 整改前后照片 before/afterPhotos 必填
     * (前端文案"上传整改前后照片形成闭环"必须真闭环, 防 level=MEDIUM/HIGH 0 照片直接翻 CHECKED).
     */
    @Transactional(rollbackFor = Exception.class)
    public XkEnvCheck check(Long id, String level, String owner, String deadline,
                            String rectifyDesc, String beforePhotos, String afterPhotos,
                            String rectifyStatus) {
        permService.requirePerm("xk:env:check");
        XkEnvCheck origin = mapper.selectById(id);
        if (origin == null) throw BizException.of("检查单不存在");
        if (Constants.STATUS_CHECKED.equals(origin.getStatus())) {
            throw BizException.of("该检查单已整改归档, 不可重复提交");
        }
        // X6: 等级≥MEDIUM 必填整改前后照片
        if ("MEDIUM".equalsIgnoreCase(level) || "HIGH".equalsIgnoreCase(level)) {
            if (StrUtil.isBlank(beforePhotos)) {
                throw BizException.of("等级 " + level + " 必须上传整改前照片 (beforePhotos)");
            }
            if (StrUtil.isBlank(afterPhotos)) {
                throw BizException.of("等级 " + level + " 必须上传整改后照片 (afterPhotos) 形成闭环");
            }
        }
        origin.setLevel(level);
        origin.setRectifyOwner(owner);
        if (StrUtil.isNotBlank(deadline)) {
            try { origin.setRectifyDeadline(java.time.LocalDate.parse(deadline)); }
            catch (Exception ignore) { /* 容错 */ }
        }
        origin.setRectifyDesc(rectifyDesc);
        if (StrUtil.isNotBlank(beforePhotos)) origin.setBeforePhotos(beforePhotos);
        if (StrUtil.isNotBlank(afterPhotos)) origin.setAfterPhotos(afterPhotos);
        origin.setRectifyStatus(StrUtil.isBlank(rectifyStatus) ? "DONE" : rectifyStatus);
        origin.setStatus(Constants.STATUS_CHECKED);
        mapper.updateById(origin);

        // 同步直写审计 (McpAuditService @Async 漏写教训)
        String desc = "车间=" + origin.getWorkshop()
                + " 类型=" + origin.getEnvType()
                + " 等级=" + (level == null ? "无" : level)
                + " 负责人=" + (owner == null ? "-" : owner);
        auditService.writeAudit("XK_ENV_CHECK", origin.getCheckNo(), "CHECK", desc, null, null, null);

        log.info("[XK] 完成环境检查 checkNo={} level={}", origin.getCheckNo(), level);
        return origin;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("xk:env:edit");  // em 模式 delete 走 edit perm
        XkEnvCheck origin = mapper.selectById(id);
        if (origin == null) throw BizException.of("检查单不存在");
        if (Constants.STATUS_CHECKED.equals(origin.getStatus())) {
            throw BizException.of("已归档的检查单不可删除, 请走回收站");
        }
        mapper.deleteById(id);
        log.info("[XK] 删除环境检查 checkNo={}", origin.getCheckNo());
    }
}
