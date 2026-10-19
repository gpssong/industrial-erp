package com.industrial.erp.modules.xk.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.xk.entity.XkSafetyCheck;
import com.industrial.erp.modules.xk.mapper.XkSafetyCheckMapper;
import com.industrial.erp.security.SecurityContext;
import com.industrial.erp.utils.BillNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * v1.1.91 XK 合规中心 — 卫生检查单 CRUD + 提交 (XK16-204 R1 受控记录)。
 *
 * <p>提交 (check) 时同步写 {@code xk_audit_log} (module=XK合规, action=SAFETY),
 * 失败 try/catch 不阻塞 (McpAuditService 教训)。R2: 只能作废 (bill_status=VOID),
 * 不允许物理删除 (走 @TableLogic 软删 + 作废双保险)。
 */
@Service
public class XkSafetyCheckService {

    private static final Logger log = LoggerFactory.getLogger(XkSafetyCheckService.class);

    private final XkSafetyCheckMapper mapper;
    private final BillNoGenerator billNoGenerator;
    private final XkAuditService xkAudit;

    public XkSafetyCheckService(XkSafetyCheckMapper mapper, BillNoGenerator billNoGenerator,
                                XkAuditService xkAudit) {
        this.mapper = mapper;
        this.billNoGenerator = billNoGenerator;
        this.xkAudit = xkAudit;
    }

    public IPage<XkSafetyCheck> page(Integer pageNum, Integer pageSize, String billStatus) {
        Page<XkSafetyCheck> p = new Page<>(pageNum, pageSize);
        return mapper.selectPage(p, new LambdaQueryWrapper<XkSafetyCheck>()
                .eq(StrUtil.isNotBlank(billStatus), XkSafetyCheck::getBillStatus, billStatus)
                .orderByDesc(XkSafetyCheck::getCheckDate));
    }

    public XkSafetyCheck detail(Long id) {
        XkSafetyCheck c = mapper.selectById(id);
        if (c == null) throw BizException.of("卫生检查单不存在");
        return c;
    }

    /** 新增卫生检查单 (DRAFT)。 */
    @Transactional(rollbackFor = Exception.class)
    public XkSafetyCheck add(XkSafetyCheck c) {
        if (StrUtil.isBlank(c.getCheckNo())) {
            c.setCheckNo(billNoGenerator.generate("AJ"));
        }
        if (c.getCheckDate() == null) c.setCheckDate(LocalDate.now());
        if (StrUtil.isBlank(c.getBillStatus())) c.setBillStatus("DRAFT");
        if (c.getInspector() == null) c.setInspector(SecurityContext.getUserId());
        if (StrUtil.isBlank(c.getInspectorName())) c.setInspectorName(SecurityContext.getUsername());
        c.setCreateBy(SecurityContext.getUserId());
        mapper.insert(c);
        log.info("[XK] 卫生检查单 add checkNo={} id={}", c.getCheckNo(), c.getId());
        return c;
    }

    /** 提交卫生检查单 (DRAFT → CHECKED), 同步写审计 (action=SAFETY)。 */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        XkSafetyCheck c = detail(id);
        if (!"DRAFT".equals(c.getBillStatus())) {
            throw BizException.of("该卫生检查单不是草稿, 无法提交");
        }
        XkSafetyCheck upd = new XkSafetyCheck();
        upd.setId(id);
        upd.setBillStatus("CHECKED");
        upd.setUpdateBy(SecurityContext.getUserId());
        mapper.updateById(upd);
        xkAudit.writeAudit("SAFETY", c.getCheckNo(), "SAFETY",
                "提交卫生检查 " + c.getCheckNo() + " 结果=" + c.getResult(), null, null, null);
    }

    /** 作废 (R2: 不允许物理删除, 只能作废保留原始记录)。 */
    @Transactional(rollbackFor = Exception.class)
    public void voidCheck(Long id, String reason) {
        XkSafetyCheck c = detail(id);
        if ("VOID".equals(c.getBillStatus())) {
            throw BizException.of("该卫生检查单已作废");
        }
        XkSafetyCheck upd = new XkSafetyCheck();
        upd.setId(id);
        upd.setBillStatus("VOID");
        upd.setRemark(StrUtil.isBlank(reason) ? c.getRemark() : (c.getRemark() + " 作废: " + reason));
        upd.setUpdateBy(SecurityContext.getUserId());
        mapper.updateById(upd);
        xkAudit.writeAudit("SAFETY", c.getCheckNo(), "VOID",
                "作废卫生检查 " + c.getCheckNo() + (StrUtil.isBlank(reason) ? "" : " 原因: " + reason),
                null, null, null);
    }
}
