package com.industrial.erp.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysFeiePrintTemplate;
import com.industrial.erp.modules.system.mapper.SysFeiePrintTemplateMapper;
import freemarker.core.ParseException;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

/**
 * 飞鹅云打印模板 Service
 *
 * <p>v1.1.24+: {@code save} / {@code update} 涉及"先 clearDefault 改 N 行 + 再 insert/update" 多步写,
 * 必须包到事务里, 否则 clearDefault 成功 + insert 失败会留下"旧默认被改 0 + 新默认未建"的脏状态
 * (下次调用 getActive 找不到默认模板). {@code delete} 也加了事务 — 后续如改"级联删关联"也安全.
 *
 * <p>v1.1.162+: save/update 先用 FreeMarker 试解析 {@code content}. 之前语法错误只能等到打印/预览才暴露,
 * 且报错行号是渲染期的 "custom" 模板行号, 现场改模板的人对着界面对不上位置.
 */
@Service
public class SysFeiePrintTemplateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SysFeiePrintTemplateService.class);

    private final SysFeiePrintTemplateMapper mapper;
    private final Configuration freemarkerConfig;

    public SysFeiePrintTemplateService(SysFeiePrintTemplateMapper mapper,
                                       @Qualifier("feieFreemarkerConfig") Configuration freemarkerConfig) {
        this.mapper = mapper;
        this.freemarkerConfig = freemarkerConfig;
    }

    /**
     * 试解析模板内容; 语法错误直接拒绝落库, 报错带上 行/列 + 出错那一行的原文.
     *
     * <p>FreeMarker 的语法错 {@code ParseException} 继承 {@code IOException}
     * ({@code Template} 构造器只声明 {@code throws IOException}), 不是 {@code TemplateException}.
     */
    private void validateContent(SysFeiePrintTemplate t) {
        String content = t.getContent();
        if (content == null || content.isEmpty()) {
            return;
        }
        try {
            new Template("validate", new StringReader(content), freemarkerConfig);
        } catch (ParseException e) {
            throw BizException.of(400, "模板语法有误: " + describe(e, content));
        } catch (IOException e) {
            throw BizException.of(400, "模板内容无法解析: " + e.getMessage());
        }
    }

    /** 取 ParseException 的行列 + 出错那行原文; FreeMarker 原文里 "but was expecting ..." 一长串枚举对用户没用, 截掉 */
    private static String describe(ParseException e, String content) {
        int lineNo = e.lineNumber;
        StringBuilder sb = new StringBuilder();
        if (lineNo > 0) {
            sb.append("第 ").append(lineNo).append(" 行");
            if (e.columnNumber > 0) sb.append(" 第 ").append(e.columnNumber).append(" 列");
            sb.append(" — ");
        }
        sb.append(brief(e.getMessage()));
        if (lineNo > 0) {
            List<String> lines = content.lines().toList();
            if (lineNo <= lines.size()) sb.append("；该行内容: ").append(lines.get(lineNo - 1));
        }
        log.warn("[FeieTpl] 模板语法校验失败: {}", e.getMessage());
        return sb.toString();
    }

    private static String brief(String msg) {
        if (msg == null || msg.isEmpty()) return "FreeMarker 解析失败";
        int cut = msg.indexOf("but was expecting");
        String s = cut > 0 ? msg.substring(0, cut).trim() : msg;
        int firstBrace = s.indexOf(':');
        if (firstBrace >= 0 && firstBrace < s.length() - 1) s = s.substring(firstBrace + 1).trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
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
        validateContent(t);
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
        validateContent(t);
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