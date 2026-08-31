package com.industrial.erp.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import com.industrial.erp.modules.system.mapper.SysFeiePrintTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 飞鹅云打印模板 Service
 *
 * <p>v1.1.24+: {@code save} / {@code update} 涉及"先 clearDefault 改 N 行 + 再 insert/update" 多步写,
 * 必须包到事务里, 否则 clearDefault 成功 + insert 失败会留下"旧默认被改 0 + 新默认未建"的脏状态
 * (下次调用 getActive 找不到默认模板). {@code delete} 也加了事务 — 后续如改"级联删关联"也安全.
 */
@Service
public class SysFeiePrintTemplateService {

    private final SysFeiePrintTemplateMapper mapper;

    public SysFeiePrintTemplateService(SysFeiePrintTemplateMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查找默认模板 (供 FeiePrintService 加载用户自定义内容)
     */
    public SysFeiePrintTemplate findDefault(String bizType, Long printerConfigId) {
        return mapper.selectDefault(bizType, printerConfigId);
    }

    public IPage<SysFeiePrintTemplate> page(int pageNum, int pageSize, String bizType, Long printerConfigId) {
        Page<SysFeiePrintTemplate> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysFeiePrintTemplate> w = new LambdaQueryWrapper<>();
        if (bizType != null && !bizType.isEmpty()) {
            w.eq(SysFeiePrintTemplate::getBizType, bizType);
        }
        if (printerConfigId != null) {
            w.eq(SysFeiePrintTemplate::getPrinterConfigId, printerConfigId);
        }
        w.eq(SysFeiePrintTemplate::getDeleted, 0);
        w.orderByDesc(SysFeiePrintTemplate::getUpdateTime);
        return mapper.selectPage(page, w);
    }

    public SysFeiePrintTemplate get(Long id) {
        SysFeiePrintTemplate t = mapper.selectById(id);
        if (t == null) throw BizException.of("模板不存在: id=" + id);
        return t;
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(SysFeiePrintTemplate t) {
        // 如果设默认, 把同 (bizType, printerConfigId) 的其他默认取消
        if (t.getIsDefault() != null && t.getIsDefault() == 1) {
            clearDefault(t.getBizType(), t.getPrinterConfigId(), t.getId());
        }
        // v1.1.30+: 诊断 — 记录入参 content 长度
        if (t.getContent() != null) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "[FeieTpl#save] bizType={}, contentLen={}, contentHead={}",
                t.getBizType(), t.getContent().length(),
                t.getContent().length() > 60 ? t.getContent().substring(0, 60) : t.getContent()
            );
        }
        mapper.insert(t);
        // v1.1.30+: 回查验证
        SysFeiePrintTemplate check = mapper.selectById(t.getId());
        if (check != null && t.getContent() != null) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "[FeieTpl#save] AFTER id={}, DB.contentLen={}, match={}",
                t.getId(), check.getContent() == null ? -1 : check.getContent().length(),
                java.util.Objects.equals(check.getContent(), t.getContent())
            );
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SysFeiePrintTemplate t) {
        if (t.getIsDefault() != null && t.getIsDefault() == 1) {
            clearDefault(t.getBizType(), t.getPrinterConfigId(), t.getId());
        }
        // v1.1.30+: 诊断 content 丢失问题 — 记录入参 content 长度与字段值
        if (t.getContent() != null) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "[FeieTpl#update] id={}, bizType={}, contentLen={}, contentHead={}, contentTail={}",
                t.getId(), t.getBizType(), t.getContent().length(),
                t.getContent().length() > 60 ? t.getContent().substring(0, 60) : t.getContent(),
                t.getContent().length() > 60 ? t.getContent().substring(t.getContent().length() - 60) : ""
            );
        } else {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "[FeieTpl#update] id={}, content is NULL! 全局 update-strategy=not_null 会跳过 content",
                t.getId()
            );
        }
        // v1.1.30+: 避免 updateById 字段策略/触发器/Hook 副作用 — 改用 UpdateWrapper 显式更新
        //   之前用 updateById(t), MyBatis-Plus 全局 update-strategy=not_null 会跳过 null 字段,
        //   看似没问题, 但 clearDefault 内的 updateById(o) 会把"被取消默认"的模板整行重写一次
        //   (o 里除了 id/isDefault 其他字段都是 DB 旧值, 没有问题).
        //   这里改用 UpdateWrapper 显式指定 SET 子句, 排除任何隐藏副作用.
        t.setIsDefault(null);
        mapper.update(null, new LambdaUpdateWrapper<SysFeiePrintTemplate>()
                .eq(SysFeiePrintTemplate::getId, t.getId())
                .set(SysFeiePrintTemplate::getName, t.getName())
                .set(SysFeiePrintTemplate::getBizType, t.getBizType())
                .set(SysFeiePrintTemplate::getPrinterConfigId, t.getPrinterConfigId())
                .set(SysFeiePrintTemplate::getContent, t.getContent())
                .set(SysFeiePrintTemplate::getPaperWidth, t.getPaperWidth())
                .set(SysFeiePrintTemplate::getStatus, t.getStatus())
                .set(SysFeiePrintTemplate::getRemark, t.getRemark())
        );
        // v1.1.30+: 回查验证 content 是否真的写入了 (排除 MyBatis-Plus 字段策略/触发器/Hook 副作用)
        SysFeiePrintTemplate check = mapper.selectById(t.getId());
        if (check != null && t.getContent() != null) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "[FeieTpl#update] AFTER id={}, DB.contentLen={}, match={}",
                t.getId(), check.getContent() == null ? -1 : check.getContent().length(),
                java.util.Objects.equals(check.getContent(), t.getContent())
            );
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysFeiePrintTemplate existing = mapper.selectById(id);
        if (existing == null) throw BizException.of("模板不存在: id=" + id);
        existing.setDeleted(1);
        mapper.updateById(existing);
    }

    /**
     * 把同 (bizType, printerConfigId) 的其他模板的 is_default 改为 0
     */
    private void clearDefault(String bizType, Long printerConfigId, Long excludeId) {
        List<SysFeiePrintTemplate> others = mapper.selectList(
            new LambdaQueryWrapper<SysFeiePrintTemplate>()
                .eq(SysFeiePrintTemplate::getBizType, bizType)
                .eq(SysFeiePrintTemplate::getPrinterConfigId, printerConfigId)
                .eq(SysFeiePrintTemplate::getIsDefault, 1)
                .ne(excludeId != null, SysFeiePrintTemplate::getId, excludeId)
                .eq(SysFeiePrintTemplate::getDeleted, 0)
        );
        for (SysFeiePrintTemplate o : others) {
            o.setIsDefault(0);
            mapper.updateById(o);
        }
    }
}