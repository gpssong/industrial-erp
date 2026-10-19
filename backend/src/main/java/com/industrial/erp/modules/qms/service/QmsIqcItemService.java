package com.industrial.erp.modules.qms.service;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import com.industrial.erp.modules.qms.mapper.QmsIqcItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * v1.1.101 XK — IQC 检验项明细 service.
 *
 * <p>App 端提交 ① saveDraft (DRAFT) ② submitAndInbound (CHECKED) 两条路径均会调 batchSave.
 */
@Service
public class QmsIqcItemService {

    private final QmsIqcItemMapper iqcItemMapper;

    public QmsIqcItemService(QmsIqcItemMapper iqcItemMapper) {
        this.iqcItemMapper = iqcItemMapper;
    }

    /** 列出某 IQC 全部检验项 (按 line_no). */
    @Transactional(readOnly = true)
    public List<QmsIqcItem> listByIqcId(Long iqcId) {
        if (iqcId == null) return java.util.Collections.emptyList();
        return iqcItemMapper.selectByIqcId(iqcId);
    }

    /**
     * 全量替换某 IQC 的检验项 (App 端保存草稿 / 提交用).
     * <p>先物理清空 deleted=0 行, 再批量 insert 新行 (避免主键冲突 + 简化 lineNo 重排).</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchSave(Long iqcId, List<QmsIqcItem> items) {
        if (iqcId == null) throw BizException.of("IQC id 不能为空");
        // 清空旧行 (不软删 — 简化历史留痕; 一期体量小)
        iqcItemMapper.deleteByIqcId(iqcId);
        if (items == null || items.isEmpty()) return;
        int line = 1;
        for (QmsIqcItem it : items) {
            if (it == null) continue;
            it.setId(null);
            it.setIqcId(iqcId);
            it.setLineNo(line++);
            if (StrUtil.isBlank(it.getSource())) it.setSource("MANUAL");
            if (it.getPassFlag() == null) it.setPassFlag(1);
            iqcItemMapper.insert(it);
        }
    }

    /** 校验某 IQC 是否全检项合格 (PASS), 一项不合格即 false. */
    @Transactional(readOnly = true)
    public boolean allPassed(Long iqcId) {
        if (iqcId == null) return false;
        return iqcItemMapper.countFailByIqcId(iqcId) == 0;
    }

    /** 物理清空 (QmsInspectionService.loadDefaultIqcItems 用). */
    @Transactional(rollbackFor = Exception.class)
    public void wipeByIqcId(Long iqcId) {
        if (iqcId != null) iqcItemMapper.deleteByIqcId(iqcId);
    }
}
