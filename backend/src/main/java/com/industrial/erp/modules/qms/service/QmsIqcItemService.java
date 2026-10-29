package com.industrial.erp.modules.qms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.qms.entity.MesQualityInspection;
import com.industrial.erp.modules.qms.entity.QmsIqcItem;
import com.industrial.erp.modules.qms.mapper.MesQualityInspectionMapper;
import com.industrial.erp.modules.qms.mapper.QmsIqcItemMapper;
import com.industrial.erp.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class QmsIqcItemService {
    private final QmsIqcItemMapper iqcItemMapper;
    private final MesQualityInspectionMapper inspectionMapper;

    public QmsIqcItemService(QmsIqcItemMapper iqcItemMapper, MesQualityInspectionMapper inspectionMapper) {
        this.iqcItemMapper = iqcItemMapper;
        this.inspectionMapper = inspectionMapper;
    }

    @Transactional(readOnly = true)
    public List<QmsIqcItem> listByIqcId(Long iqcId) {
        if (iqcId == null) return java.util.Collections.emptyList();
        return iqcItemMapper.selectByIqcId(iqcId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchSave(Long iqcId, List<QmsIqcItem> items) {
        MesQualityInspection head = lockDraft(iqcId);
        if (isTemplateManaged(head)) {
            List<QmsIqcItem> existing = iqcItemMapper.selectByIqcId(iqcId);
            Map<String, QmsIqcItem> submitted = validateTemplateSubmission(existing, items);
            for (QmsIqcItem row : existing) {
                QmsIqcItem input = submitted.get(row.getItemCode());
                int updated = iqcItemMapper.update(null, new LambdaUpdateWrapper<QmsIqcItem>()
                        .eq(QmsIqcItem::getId, row.getId())
                        .eq(QmsIqcItem::getIqcId, iqcId)
                        .set(QmsIqcItem::getResultValue, input.getResultValue())
                        .set(QmsIqcItem::getPassFlag, input.getPassFlag() == null ? -1 : input.getPassFlag())
                        .set(QmsIqcItem::getJudgmentBasis, input.getJudgmentBasis())
                        .set(QmsIqcItem::getUpdateBy, SecurityContext.getUserId())
                        .set(QmsIqcItem::getUpdateTime, LocalDateTime.now())
                        .setSql("version = version + 1"));
                if (updated != 1) throw BizException.of("检验项已变化，请刷新后重试");
            }
            return;
        }
        if (items != null) for (QmsIqcItem item : items) validateItem(item);
        iqcItemMapper.deleteByIqcId(iqcId);
        if (items == null || items.isEmpty()) return;
        int line = 1;
        for (QmsIqcItem it : items) {
            it.setId(null);
            it.setIqcId(iqcId);
            it.setLineNo(line++);
            it.setDeleted(0);
            it.setVersion(0);
            if (StrUtil.isBlank(it.getSource())) it.setSource("MANUAL");
            if (it.getPassFlag() == null) it.setPassFlag(-1);
            if (it.getIsFoodGradeItem() == null) it.setIsFoodGradeItem(0);
            iqcItemMapper.insert(it);
        }
    }

    static boolean isProcessInspection(MesQualityInspection head) {
        return Constants.INSPECTION_FAI.equals(head.getInspectionType())
                || Constants.INSPECTION_PAT.equals(head.getInspectionType());
    }

    /**
     * v1.1.131 — 服务器模板托管的检验类型: FAI/PAT 工序检 + OQC/OQC_RETURN 成品检.
     * 这些单据的检验项定义 (名称/要求/方法/单位/顺序) 由后端模板固化, 客户端只能回填实测值与判定,
     * 不能删项、改定义或用手工项替代 (食品接触许可要求出厂检验项固定可追溯)。
     */
    static boolean isTemplateManaged(MesQualityInspection head) {
        return isProcessInspection(head)
                || Constants.INSPECTION_OQC.equals(head.getInspectionType())
                || Constants.INSPECTION_OQC_RETURN.equals(head.getInspectionType());
    }

    static Map<String, QmsIqcItem> validateTemplateSubmission(List<QmsIqcItem> existing, List<QmsIqcItem> items) {
        if (existing == null || existing.isEmpty()) throw BizException.of("检验单缺少服务器模板项，不能手工替代");
        if (items == null || items.size() != existing.size()) throw BizException.of("必须提交完整检验项，不允许删除模板项");
        Map<String, QmsIqcItem> submitted = new HashMap<>();
        for (QmsIqcItem item : items) {
            validateItem(item);
            if (StrUtil.isBlank(item.getItemCode()) || submitted.put(item.getItemCode(), item) != null) {
                throw BizException.of("检验项编码为空或重复");
            }
        }
        for (QmsIqcItem row : existing) {
            QmsIqcItem input = submitted.get(row.getItemCode());
            if (!"TEMPLATE".equals(row.getSource()) || input == null) {
                throw BizException.of("模板检验项不完整或包含手工替代项");
            }
            if ((input.getId() != null && !Objects.equals(row.getId(), input.getId()))
                    || (input.getIqcId() != null && !Objects.equals(row.getIqcId(), input.getIqcId()))
                    || changed(input.getItemName(), row.getItemName())
                    || changed(input.getRequirement(), row.getRequirement())
                    || changed(input.getMethod(), row.getMethod())
                    || changed(input.getUnit(), row.getUnit())
                    || changed(input.getSource(), row.getSource())
                    || changed(input.getIsFoodGradeItem(), row.getIsFoodGradeItem())
                    || changed(input.getLineNo(), row.getLineNo())
                    || (input.getDeleted() != null && input.getDeleted() != 0)) {
                throw BizException.of("不允许修改服务器模板定义或检验项归属");
            }
        }
        return submitted;
    }

    private static boolean changed(Object input, Object stored) {
        return input != null && !Objects.equals(input, stored);
    }

    static void validateItem(QmsIqcItem item) {
        if (item == null) throw BizException.of("检验项不能为空");
        requireLength(item.getItemCode(), 64, "检验项编码");
        requireLength(item.getItemName(), 64, "检验项名称");
        requireLength(item.getRequirement(), 255, "检验要求");
        requireLength(item.getMethod(), 64, "检验方法");
        requireLength(item.getUnit(), 32, "检验单位");
        requireLength(item.getSource(), 32, "检验项来源");
        requireLength(item.getResultValue(), 128, "实测值");
        requireLength(item.getJudgmentBasis(), 255, "判定依据");
        if (item.getPassFlag() != null && item.getPassFlag() != -1 && item.getPassFlag() != 0 && item.getPassFlag() != 1) {
            throw BizException.of("检验项判定仅支持 -1待检、0不合格、1合格");
        }
    }

    static void requireLength(String value, int max, String field) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw BizException.of(field + "不能超过" + max + "个字符");
        }
    }

    private MesQualityInspection lockDraft(Long iqcId) {
        if (iqcId == null) throw BizException.of("检验单 id 不能为空");
        MesQualityInspection head = inspectionMapper.selectByIdForUpdate(iqcId);
        if (head == null) throw BizException.of("检验单不存在");
        if (!Constants.STATUS_DRAFT.equals(head.getStatus())) throw BizException.of("仅草稿检验单可修改，已签核记录只读");
        return head;
    }

    @Transactional(readOnly = true)
    public boolean allPassed(Long iqcId) {
        if (iqcId == null) return false;
        List<QmsIqcItem> items = iqcItemMapper.selectByIqcId(iqcId);
        return !items.isEmpty() && items.stream().allMatch(it -> Integer.valueOf(1).equals(it.getPassFlag())
                && StrUtil.isNotBlank(it.getResultValue()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void wipeByIqcId(Long iqcId) {
        MesQualityInspection head = lockDraft(iqcId);
        if (isTemplateManaged(head)) throw BizException.of("不允许删除服务器模板检验项 (工序检/成品检)");
        iqcItemMapper.deleteByIqcId(iqcId);
    }
}
