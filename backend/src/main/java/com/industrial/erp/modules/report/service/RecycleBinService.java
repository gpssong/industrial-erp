package com.industrial.erp.modules.report.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.report.mapper.RecycleBinMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.68 回收站 service.
 *
 * <p>列表: 一个端点返回全部 11 类被删单据 (按 type 分组).
 * 恢复: head+detail 的 deleted 翻回 0 (因业务删除只对 DRAFT 开放, 无库存/账副作用, 无需回滚).
 * 彻底删: 物理 DELETE head+detail (不可逆; 绕开 prd_order 的 (bill_no,deleted) 唯一索引撞坑).
 * 权限: v1.1.72 起用独立 report:recycle (端点级 @SaCheckPermission + 此处 service 双保险).
 *       原复用 report:view — 但回收站菜单节点若 perms 为空会在角色权限树被判为
 *       不可授权目录, 无法单独勾选/保存; 改独立码后回收站成为可授权叶子.
 */
@Service
public class RecycleBinService {

    private final RecycleBinMapper mapper;
    private final PermissionService permService;

    public RecycleBinService(RecycleBinMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }

    /** 按 type 分组返回被删单据: { pur_order: [...], sal_delivery: [...], ... }. 保持 11 类固定顺序. */
    public Map<String, List<Map<String, Object>>> listAll(String keyword, String startDate, String endDate) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (RecycleType t : RecycleType.values()) {
            List<Map<String, Object>> rows = mapper.listDeleted(
                    t.getHeadTable(),
                    t.hasDetail() ? t.getDetailTable() : null,
                    t.hasDetail() ? t.getDetailFk() : null,
                    t.hasDetail(),
                    keyword, startDate, endDate);
            result.put(t.getKey(), rows == null ? new ArrayList<>() : rows);
        }
        return result;
    }

    /** 恢复某被删单据 (head + 其明细) — 只允许已删 (deleted=1) 的行. */
    @Transactional(rollbackFor = Exception.class)
    public void restore(String type, Long id) {
        RecycleType t = requireType(type);
        permService.requirePerm("report:recycle");
        Map<String, Object> head = requireDeletedHead(t, id);
        int headRows = mapper.restoreHead(t.getHeadTable(), id);
        if (headRows == 0) {
            throw BizException.of("恢复失败: 单据不存在或已非删除状态");
        }
        if (t.hasDetail()) {
            mapper.restoreDetail(t.getDetailTable(), t.getDetailFk(), id);
        }
        // head 行状态仅用于回显, 不影响逻辑
        if (head == null) {
            throw BizException.of("恢复失败: 单据不存在");
        }
    }

    /** 彻底删某被删单据 (head + 其明细, 物理 DELETE, 不可逆). 只允许已删 (deleted=1) 的行. */
    @Transactional(rollbackFor = Exception.class)
    public void physicalDelete(String type, Long id) {
        RecycleType t = requireType(type);
        permService.requirePerm("report:recycle");
        requireDeletedHead(t, id);
        if (t.hasDetail()) {
            mapper.physicalDeleteDetail(t.getDetailTable(), t.getDetailFk(), id);
        }
        int headRows = mapper.physicalDeleteHead(t.getHeadTable(), id);
        if (headRows == 0) {
            throw BizException.of("彻底删除失败: 单据不存在或已非删除状态");
        }
    }

    private RecycleType requireType(String type) {
        RecycleType t = RecycleType.fromKey(type);
        if (t == null) {
            throw BizException.of("未知的单据类型: " + type);
        }
        return t;
    }

    /** 校验 head 行存在且确实处于已删 (deleted=1) 状态; 否则拒绝操作. 返回该行 (供调用方参考). */
    private Map<String, Object> requireDeletedHead(RecycleType t, Long id) {
        Map<String, Object> row = mapper.peekHead(t.getHeadTable(), id);
        if (row == null) {
            throw BizException.of("单据不存在 (可能已被彻底删除): id=" + id);
        }
        Object deleted = row.get("deleted");
        int d = (deleted == null) ? 0 : ((Number) deleted).intValue();
        if (d != 1) {
            throw BizException.of("该单据当前不是删除状态, 无法在回收站操作");
        }
        return row;
    }
}
