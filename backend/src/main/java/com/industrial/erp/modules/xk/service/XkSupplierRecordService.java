package com.industrial.erp.modules.xk.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.xk.entity.XkSupplierCert;
import com.industrial.erp.modules.xk.entity.XkSupplierPerf;
import com.industrial.erp.modules.xk.entity.XkSupplierReview;
import com.industrial.erp.modules.xk.mapper.XkSupplierCertMapper;
import com.industrial.erp.modules.xk.mapper.XkSupplierPerfMapper;
import com.industrial.erp.modules.xk.mapper.XkSupplierReviewMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.1.130 P1/P2 XK 合格供应商 — 准入评审记录 / 证照与检测报告 / 年度绩效评分 + 到期看板.
 *
 * <p>三张表各自独立留档; 生产许可类证照保存后回写 {@code base_supplier.food_cert_*},
 * 让采购订单/入库门禁与证照档案共用同一份口径。绩效总分与 A/B/C 等级由服务端计算 (前端只交维度分)。
 */
@Service
public class XkSupplierRecordService {

    /** 资质 / 复评临期预警窗口 (天) */
    public static final int WARN_DAYS = 90;

    private static final Set<String> REVIEW_TYPES = Set.of("INITIAL", "ANNUAL", "SPECIAL");
    private static final Set<String> REVIEW_RESULTS = Set.of("PASS", "CONDITIONAL", "FAIL");
    private static final Set<String> CERT_TYPES = Set.of(
            "PRODUCTION_LICENSE", "GB4806_REPORT", "CONFORMITY_DECLARATION", "ISO22000", "OTHER");

    private static final Map<String, String> CERT_TYPE_LABEL = Map.of(
            "PRODUCTION_LICENSE", "生产许可",
            "GB4806_REPORT", "GB 4806 检测报告",
            "CONFORMITY_DECLARATION", "符合性声明",
            "ISO22000", "ISO 22000 认证",
            "OTHER", "其他");

    private static final BigDecimal W_QUALITY = new BigDecimal("35");
    private static final BigDecimal W_DELIVERY = new BigDecimal("20");
    private static final BigDecimal W_PRICE = new BigDecimal("15");
    private static final BigDecimal W_SERVICE = new BigDecimal("15");
    private static final BigDecimal W_COMPLIANCE = new BigDecimal("15");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal GRADE_A = new BigDecimal("90");
    private static final BigDecimal GRADE_B = new BigDecimal("75");

    private final XkSupplierReviewMapper reviewMapper;
    private final XkSupplierCertMapper certMapper;
    private final XkSupplierPerfMapper perfMapper;
    private final BaseSupplierMapper supplierMapper;
    private final PermissionService permService;
    private final XkAuditService auditService;

    public XkSupplierRecordService(XkSupplierReviewMapper reviewMapper,
                                   XkSupplierCertMapper certMapper,
                                   XkSupplierPerfMapper perfMapper,
                                   BaseSupplierMapper supplierMapper,
                                   PermissionService permService,
                                   XkAuditService auditService) {
        this.reviewMapper = reviewMapper;
        this.certMapper = certMapper;
        this.perfMapper = perfMapper;
        this.supplierMapper = supplierMapper;
        this.permService = permService;
        this.auditService = auditService;
    }

    // ==================================================================
    // 统计 + 到期看板
    // ==================================================================

    /** 名录 5 模块角标: 供应商总数 / 合格 / 暂停 / 待复评 / 证照记录数 / 绩效记录数 */
    public Map<String, Object> stats() {
        permService.requirePerm("base:supplier:list");
        Map<String, Object> d = dashboard();
        long total = supplierMapper.selectCount(null);
        long qualified = supplierMapper.selectCount(new LambdaQueryWrapper<BaseSupplier>()
                .eq(BaseSupplier::getStatus, 1));
        long suspended = supplierMapper.selectCount(new LambdaQueryWrapper<BaseSupplier>()
                .and(w -> w.ne(BaseSupplier::getStatus, 1).or().isNull(BaseSupplier::getStatus)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total);
        r.put("qualified", qualified);
        r.put("suspended", suspended);
        r.put("dueReview", d.get("reviewDueCount"));
        r.put("withCert", certMapper.selectCount(null));
        r.put("rated", perfMapper.selectCount(null));
        r.put("warnDays", WARN_DAYS);
        r.put("expiring", d.get("certs"));
        return r;
    }

    /**
     * 到期看板: 证照临期 / 复评到期 / 最新评级分布 / 名录列所需的最新评审与评级。
     * 一次取回, 供 Material.vue 顶部预警条 + 名录列 + 三个记录面板共用。
     */
    public Map<String, Object> dashboard() {
        permService.requirePerm("base:supplier:list");
        LocalDate today = LocalDate.now();
        LocalDate warnLine = today.plusDays(WARN_DAYS);

        // 证照临期 (含已过期, 按到期日升序)
        List<XkSupplierCert> certRows = certMapper.selectList(new LambdaQueryWrapper<XkSupplierCert>()
                .isNotNull(XkSupplierCert::getExpireDate)
                .le(XkSupplierCert::getExpireDate, warnLine)
                .orderByAsc(XkSupplierCert::getExpireDate)
                .last("LIMIT 100"));
        List<Map<String, Object>> certs = new ArrayList<>();
        for (XkSupplierCert c : certRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(c.getId()));
            m.put("supplierId", String.valueOf(c.getSupplierId()));
            m.put("supplierCode", c.getSupplierCode());
            m.put("supplierName", c.getSupplierName());
            m.put("certType", c.getCertType());
            m.put("certTypeLabel", CERT_TYPE_LABEL.getOrDefault(c.getCertType(), "其他"));
            m.put("certNo", c.getCertNo());
            m.put("expireDate", c.getExpireDate());
            m.put("daysLeft", ChronoUnit.DAYS.between(today, c.getExpireDate()));
            certs.add(m);
        }

        // 每家供应商最近一次评审 (按 supplier_id, review_date DESC 取首条)
        List<XkSupplierReview> allReviews = reviewMapper.selectList(new LambdaQueryWrapper<XkSupplierReview>()
                .orderByDesc(XkSupplierReview::getReviewDate)
                .orderByDesc(XkSupplierReview::getId)
                .last("LIMIT 2000"));
        Map<Long, XkSupplierReview> latestReview = new LinkedHashMap<>();
        for (XkSupplierReview rv : allReviews) {
            latestReview.putIfAbsent(rv.getSupplierId(), rv);
        }

        List<BaseSupplier> qualifiedSuppliers = supplierMapper.selectList(new LambdaQueryWrapper<BaseSupplier>()
                .eq(BaseSupplier::getStatus, 1)
                .orderByAsc(BaseSupplier::getSupplierName));
        List<Map<String, Object>> reviews = new ArrayList<>();
        long reviewDueCount = 0;
        for (BaseSupplier s : qualifiedSuppliers) {
            XkSupplierReview rv = latestReview.get(s.getId());
            boolean due = rv == null
                    || rv.getNextReviewDate() == null
                    || !rv.getNextReviewDate().isAfter(warnLine);
            if (due) reviewDueCount++;
            if (!due) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", String.valueOf(s.getId()));
            m.put("supplierCode", s.getSupplierCode());
            m.put("supplierName", s.getSupplierName());
            m.put("reviewDate", rv == null ? null : rv.getReviewDate());
            m.put("result", rv == null ? null : rv.getResult());
            m.put("nextReviewDate", rv == null ? null : rv.getNextReviewDate());
            m.put("neverReviewed", rv == null);
            m.put("daysLeft", rv == null || rv.getNextReviewDate() == null
                    ? null : ChronoUnit.DAYS.between(today, rv.getNextReviewDate()));
            reviews.add(m);
            if (reviews.size() >= 100) break;
        }

        // 每家供应商最近一次绩效 (eval_year DESC, id DESC)
        List<XkSupplierPerf> allPerf = perfMapper.selectList(new LambdaQueryWrapper<XkSupplierPerf>()
                .orderByDesc(XkSupplierPerf::getEvalYear)
                .orderByDesc(XkSupplierPerf::getId)
                .last("LIMIT 2000"));
        Map<Long, XkSupplierPerf> latestPerf = new LinkedHashMap<>();
        for (XkSupplierPerf p : allPerf) {
            latestPerf.putIfAbsent(p.getSupplierId(), p);
        }
        List<Map<String, Object>> latestRating = new ArrayList<>();
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("A", 0);
        dist.put("B", 0);
        dist.put("C", 0);
        for (XkSupplierPerf p : latestPerf.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", String.valueOf(p.getSupplierId()));
            m.put("evalYear", p.getEvalYear());
            m.put("grade", p.getGrade());
            m.put("totalScore", p.getTotalScore());
            m.put("nextReviewDate", p.getNextReviewDate());
            latestRating.add(m);
            dist.merge(p.getGrade(), 1, Integer::sum);
        }

        Map<String, Object> latestReviewMap = new LinkedHashMap<>();
        for (Map.Entry<Long, XkSupplierReview> e : latestReview.entrySet()) {
            XkSupplierReview rv = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reviewDate", rv.getReviewDate());
            m.put("result", rv.getResult());
            m.put("nextReviewDate", rv.getNextReviewDate());
            latestReviewMap.put(String.valueOf(e.getKey()), m);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("warnDays", WARN_DAYS);
        r.put("certs", certs);
        r.put("certDueCount", certs.size());
        r.put("reviews", reviews);
        r.put("reviewDueCount", reviewDueCount);
        r.put("latestRating", latestRating);
        r.put("latestReview", latestReviewMap);
        r.put("ratingDistribution", dist);
        return r;
    }

    // ==================================================================
    // 准入评审记录
    // ==================================================================

    public List<XkSupplierReview> listReviews(Long supplierId, String keyword, Integer limit) {
        permService.requirePerm("base:supplier:list");
        LambdaQueryWrapper<XkSupplierReview> w = new LambdaQueryWrapper<>();
        if (supplierId != null) w.eq(XkSupplierReview::getSupplierId, supplierId);
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(XkSupplierReview::getSupplierName, keyword)
                    .or().like(XkSupplierReview::getSupplierCode, keyword)
                    .or().like(XkSupplierReview::getReviewer, keyword));
        }
        w.orderByDesc(XkSupplierReview::getReviewDate).orderByDesc(XkSupplierReview::getId);
        w.last("LIMIT " + clampLimit(limit));
        return reviewMapper.selectList(w);
    }

    @Transactional(rollbackFor = Exception.class)
    public XkSupplierReview saveReview(XkSupplierReview r) {
        permService.requirePerm("base:supplier:edit");
        if (r == null) throw BizException.of("评审记录不能为空");
        BaseSupplier s = requireSupplier(r.getSupplierId());
        String type = StrUtil.blankToDefault(r.getReviewType(), "ANNUAL").toUpperCase();
        if (!REVIEW_TYPES.contains(type)) throw BizException.of("评审类型应为 INITIAL / ANNUAL / SPECIAL");
        String result = StrUtil.blankToDefault(r.getResult(), "PASS").toUpperCase();
        if (!REVIEW_RESULTS.contains(result)) throw BizException.of("评审结论应为 PASS / CONDITIONAL / FAIL");
        if (StrUtil.isBlank(r.getReviewer())) throw BizException.of("请填写评审人");
        if (r.getReviewDate() == null) r.setReviewDate(LocalDate.now());
        if (r.getNextReviewDate() == null) {
            r.setNextReviewDate("SPECIAL".equals(type)
                    ? r.getReviewDate().plusMonths(6) : r.getReviewDate().plusYears(1));
        }

        r.setSupplierCode(s.getSupplierCode());
        r.setSupplierName(s.getSupplierName());
        r.setReviewType(type);
        r.setResult(result);
        r.setConclusion(StrUtil.maxLength(r.getConclusion(), 1000));
        r.setAttachmentUrl(StrUtil.maxLength(r.getAttachmentUrl(), 500));
        r.setOperator(SecurityContext.getUserId());
        r.setOperatorName(SecurityContext.getUsername());
        r.setOperTime(LocalDateTime.now());

        boolean isNew = r.getId() == null;
        if (isNew) reviewMapper.insert(r); else reviewMapper.updateById(r);

        auditService.writeAudit("SUPPLIER_REVIEW", s.getSupplierCode(), isNew ? "ADD" : "EDIT",
                (isNew ? "新增" : "修改") + "准入评审 [" + reviewLabel(type) + "] 结论 " + resultLabel(result)
                        + " / 下次复评 " + r.getNextReviewDate()
                        + (StrUtil.isNotBlank(r.getConclusion()) ? ": " + r.getConclusion() : ""),
                null, r.getAttachmentUrl(), null);
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteReview(Long id) {
        permService.requirePerm("base:supplier:edit");
        XkSupplierReview r = id == null ? null : reviewMapper.selectById(id);
        if (r == null) throw BizException.of("评审记录不存在或已删除");
        reviewMapper.deleteById(id);
        auditService.writeAudit("SUPPLIER_REVIEW", r.getSupplierCode(), "DELETE",
                "删除准入评审记录 " + r.getReviewDate() + " / 结论 " + resultLabel(r.getResult()),
                null, null, null);
    }

    // ==================================================================
    // 证照与检测报告
    // ==================================================================

    public List<XkSupplierCert> listCerts(Long supplierId, String certType, String keyword, Integer limit) {
        permService.requirePerm("base:supplier:list");
        LambdaQueryWrapper<XkSupplierCert> w = new LambdaQueryWrapper<>();
        if (supplierId != null) w.eq(XkSupplierCert::getSupplierId, supplierId);
        if (StrUtil.isNotBlank(certType)) w.eq(XkSupplierCert::getCertType, certType);
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(XkSupplierCert::getSupplierName, keyword)
                    .or().like(XkSupplierCert::getSupplierCode, keyword)
                    .or().like(XkSupplierCert::getCertNo, keyword)
                    .or().like(XkSupplierCert::getCertName, keyword));
        }
        w.orderByAsc(XkSupplierCert::getExpireDate).orderByDesc(XkSupplierCert::getId);
        w.last("LIMIT " + clampLimit(limit));
        return certMapper.selectList(w);
    }

    @Transactional(rollbackFor = Exception.class)
    public XkSupplierCert saveCert(XkSupplierCert c) {
        permService.requirePerm("base:supplier:edit");
        if (c == null) throw BizException.of("证照记录不能为空");
        BaseSupplier s = requireSupplier(c.getSupplierId());
        String type = StrUtil.blankToDefault(c.getCertType(), "PRODUCTION_LICENSE").toUpperCase();
        if (!CERT_TYPES.contains(type)) throw BizException.of("证照类型不合法");
        if (StrUtil.isBlank(c.getCertNo())) throw BizException.of("请填写证照 / 报告编号");
        if (c.getIssueDate() != null && c.getExpireDate() != null && c.getExpireDate().isBefore(c.getIssueDate())) {
            throw BizException.of("有效期至不能早于发证日期");
        }
        c.setCertType(type);
        c.setSupplierCode(s.getSupplierCode());
        c.setSupplierName(s.getSupplierName());
        c.setCertName(StrUtil.maxLength(c.getCertName(), 200));
        c.setIssuer(StrUtil.maxLength(c.getIssuer(), 200));
        c.setFileUrl(StrUtil.maxLength(c.getFileUrl(), 500));
        c.setRemark(StrUtil.maxLength(c.getRemark(), 500));

        boolean isNew = c.getId() == null;
        if (isNew) certMapper.insert(c); else certMapper.updateById(c);

        if ("PRODUCTION_LICENSE".equals(type)) syncSupplierFoodCert(s.getId());

        auditService.writeAudit("SUPPLIER_CERT", s.getSupplierCode(), isNew ? "ADD" : "EDIT",
                (isNew ? "登记" : "修改") + "证照 [" + CERT_TYPE_LABEL.get(type) + "] " + c.getCertNo()
                        + (c.getExpireDate() == null ? "" : " / 有效期至 " + c.getExpireDate()),
                null, c.getFileUrl(), null);
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteCert(Long id) {
        permService.requirePerm("base:supplier:edit");
        XkSupplierCert c = id == null ? null : certMapper.selectById(id);
        if (c == null) throw BizException.of("证照记录不存在或已删除");
        certMapper.deleteById(id);
        if ("PRODUCTION_LICENSE".equals(c.getCertType())) syncSupplierFoodCert(c.getSupplierId());
        auditService.writeAudit("SUPPLIER_CERT", c.getSupplierCode(), "DELETE",
                "删除证照 [" + CERT_TYPE_LABEL.getOrDefault(c.getCertType(), "其他") + "] " + c.getCertNo(),
                null, null, null);
    }

    /** 生产许可类证照变动后, 用最新一张回写 base_supplier.food_cert_* (采购门禁唯一口径)。 */
    private void syncSupplierFoodCert(Long supplierId) {
        List<XkSupplierCert> licenses = certMapper.selectList(new LambdaQueryWrapper<XkSupplierCert>()
                .eq(XkSupplierCert::getSupplierId, supplierId)
                .eq(XkSupplierCert::getCertType, "PRODUCTION_LICENSE")
                .orderByDesc(XkSupplierCert::getExpireDate)
                .orderByDesc(XkSupplierCert::getId)
                .last("LIMIT 1"));
        BaseSupplier s = supplierMapper.selectById(supplierId);
        if (s == null) return;
        if (licenses.isEmpty()) {
            // updateById 默认忽略 null, 清空必须走 UpdateWrapper 显式 set null
            supplierMapper.update(null, new LambdaUpdateWrapper<BaseSupplier>()
                    .eq(BaseSupplier::getId, supplierId)
                    .set(BaseSupplier::getFoodCertNo, null)
                    .set(BaseSupplier::getFoodCertExp, null));
        } else {
            XkSupplierCert c = licenses.get(0);
            s.setFoodCertNo(c.getCertNo());
            s.setFoodCertExp(c.getExpireDate());
            s.setFoodCertType("FOOD_PRODUCT_LICENSE");
            supplierMapper.updateById(s);
        }
    }

    // ==================================================================
    // 年度绩效评分
    // ==================================================================

    public List<XkSupplierPerf> listPerf(Integer evalYear, Long supplierId, String keyword, Integer limit) {
        permService.requirePerm("base:supplier:list");
        LambdaQueryWrapper<XkSupplierPerf> w = new LambdaQueryWrapper<>();
        if (evalYear != null) w.eq(XkSupplierPerf::getEvalYear, evalYear);
        if (supplierId != null) w.eq(XkSupplierPerf::getSupplierId, supplierId);
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(XkSupplierPerf::getSupplierName, keyword)
                    .or().like(XkSupplierPerf::getSupplierCode, keyword)
                    .or().like(XkSupplierPerf::getGrade, keyword));
        }
        w.orderByDesc(XkSupplierPerf::getEvalYear).orderByDesc(XkSupplierPerf::getTotalScore);
        w.last("LIMIT " + clampLimit(limit));
        return perfMapper.selectList(w);
    }

    @Transactional(rollbackFor = Exception.class)
    public XkSupplierPerf savePerf(XkSupplierPerf p) {
        permService.requirePerm("base:supplier:edit");
        if (p == null) throw BizException.of("绩效评分不能为空");
        BaseSupplier s = requireSupplier(p.getSupplierId());
        if (p.getEvalYear() == null) p.setEvalYear(LocalDate.now().getYear());
        if (p.getEvalYear() < 2000 || p.getEvalYear() > 2100) throw BizException.of("评价年度不合法");
        p.setQualityScore(score(p.getQualityScore(), "质量"));
        p.setDeliveryScore(score(p.getDeliveryScore(), "交付"));
        p.setPriceScore(score(p.getPriceScore(), "价格"));
        p.setServiceScore(score(p.getServiceScore(), "服务"));
        p.setComplianceScore(score(p.getComplianceScore(), "合规/资质"));
        if (p.getEvalDate() == null) p.setEvalDate(LocalDate.now());
        if (p.getNextReviewDate() == null) p.setNextReviewDate(p.getEvalDate().plusYears(1));

        computeTotal(p);
        p.setSupplierCode(s.getSupplierCode());
        p.setSupplierName(s.getSupplierName());
        p.setRemark(StrUtil.maxLength(p.getRemark(), 1000));

        boolean isNew = p.getId() == null;
        if (isNew) perfMapper.insert(p); else perfMapper.updateById(p);

        auditService.writeAudit("SUPPLIER_RATING", s.getSupplierCode(), isNew ? "ADD" : "EDIT",
                (isNew ? "新增" : "修改") + " " + p.getEvalYear() + " 年度绩效: " + p.getTotalScore() + " 分 → "
                        + p.getGrade() + " 级 (质量" + p.getQualityScore() + "/交付" + p.getDeliveryScore()
                        + "/价格" + p.getPriceScore() + "/服务" + p.getServiceScore()
                        + "/合规" + p.getComplianceScore() + "), 下次复评 " + p.getNextReviewDate(),
                null, null, null);
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePerf(Long id) {
        permService.requirePerm("base:supplier:edit");
        XkSupplierPerf p = id == null ? null : perfMapper.selectById(id);
        if (p == null) throw BizException.of("绩效评分记录不存在或已删除");
        perfMapper.deleteById(id);
        auditService.writeAudit("SUPPLIER_RATING", p.getSupplierCode(), "DELETE",
                "删除 " + p.getEvalYear() + " 年度绩效记录 (" + p.getTotalScore() + " 分 / " + p.getGrade() + " 级)",
                null, null, null);
    }

    /** 加权总分 + A/B/C 分级 (服务端计算, 不信任前端传入的 total/grade)。 */
    private void computeTotal(XkSupplierPerf p) {
        BigDecimal total = p.getQualityScore().multiply(W_QUALITY)
                .add(p.getDeliveryScore().multiply(W_DELIVERY))
                .add(p.getPriceScore().multiply(W_PRICE))
                .add(p.getServiceScore().multiply(W_SERVICE))
                .add(p.getComplianceScore().multiply(W_COMPLIANCE))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        p.setTotalScore(total);
        p.setGrade(total.compareTo(GRADE_A) >= 0 ? "A" : (total.compareTo(GRADE_B) >= 0 ? "B" : "C"));
    }

    private BigDecimal score(BigDecimal v, String label) {
        if (v == null) throw BizException.of("请填写「" + label + "」评分");
        if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(HUNDRED) > 0) {
            throw BizException.of("「" + label + "」评分应在 0-100 之间");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 公共
    // ==================================================================

    private BaseSupplier requireSupplier(Long id) {
        BaseSupplier s = id == null ? null : supplierMapper.selectById(id);
        if (s == null) throw BizException.of("供应商不存在或已删除");
        return s;
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return 200;
        return Math.min(limit, 500);
    }

    private static String reviewLabel(String type) {
        switch (type) {
            case "INITIAL": return "初次准入";
            case "SPECIAL": return "专项评审";
            default: return "年度复评";
        }
    }

    private static String resultLabel(String result) {
        switch (result == null ? "" : result) {
            case "PASS": return "通过";
            case "CONDITIONAL": return "有条件通过";
            case "FAIL": return "不通过";
            default: return result;
        }
    }
}
