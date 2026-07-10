package com.industrial.erp.modules.print.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.purchase.entity.PurReturnDetail;
import com.industrial.erp.modules.purchase.mapper.PurReturnDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReturnMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.sales.entity.SalReturnDetail;
import com.industrial.erp.modules.sales.mapper.SalReturnDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalReturnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 打印数据加载器 (单一职责: 从数据库加载单据 + 明细 + 字段映射)。
 *
 * <p>从 PrintService 中拆分出来, 避免 SQL/JDBC 细节与模板渲染逻辑混杂。
 */
@Component
public class PrintDataLoader {

    private static final Logger log = LoggerFactory.getLogger(PrintDataLoader.class);

    private final DataSource dataSource;
    private final PrdOrderMapper prdOrderMapper;
    private final PurReturnMapper purReturnMapper;
    private final PurReturnDetailMapper purReturnDetailMapper;
    private final SalReturnMapper salReturnMapper;
    private final SalReturnDetailMapper salReturnDetailMapper;

    public PrintDataLoader(DataSource dataSource, PrdOrderMapper prdOrderMapper,
                           PurReturnMapper purReturnMapper, PurReturnDetailMapper purReturnDetailMapper,
                           SalReturnMapper salReturnMapper, SalReturnDetailMapper salReturnDetailMapper) {
        this.dataSource = dataSource;
        this.prdOrderMapper = prdOrderMapper;
        this.purReturnMapper = purReturnMapper;
        this.purReturnDetailMapper = purReturnDetailMapper;
        this.salReturnMapper = salReturnMapper;
        this.salReturnDetailMapper = salReturnDetailMapper;
    }

    // ========== 单据加载 ==========

    public PurReceipt findPurReceipt(Long id) {
        // JOIN base_warehouse 取 warehouse_name / area_name, 让模板中 {{warehouseName}} 生效
        String sql = "SELECT r.*, w.warehouse_name AS _warehouse_name, a.area_name AS _area_name "
                   + "FROM pur_receipt r "
                   + "LEFT JOIN base_warehouse w ON w.id = r.warehouse_id AND w.deleted = 0 "
                   + "LEFT JOIN base_warehouse_area a ON a.id = r.area_id AND a.deleted = 0 "
                   + "WHERE r.id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRowReceipt(rs);
            }
        } catch (SQLException e) {
            throw BizException.of("查询入库单失败: " + e.getMessage());
        }
    }

    public SalDelivery findSalDelivery(Long id) {
        // JOIN base_warehouse 取 warehouse_name / area_name, 让模板中 {{warehouseName}} 生效
        String sql = "SELECT d.*, w.warehouse_name AS _warehouse_name, a.area_name AS _area_name "
                   + "FROM sal_delivery d "
                   + "LEFT JOIN base_warehouse w ON w.id = d.warehouse_id AND w.deleted = 0 "
                   + "LEFT JOIN base_warehouse_area a ON a.id = d.area_id AND a.deleted = 0 "
                   + "WHERE d.id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRowDelivery(rs);
            }
        } catch (SQLException e) {
            throw BizException.of("查询出库单失败: " + e.getMessage());
        }
    }

    public PrdOrder findPrdOrder(Long id) {
        PrdOrder order = prdOrderMapper.selectById(id);
        // 注入商品规格属性 (打印模板用)
        if (order != null && order.getProductId() != null) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT thickness, width, density, gram_weight, material FROM base_product WHERE id = ? AND deleted = 0")) {
                ps.setObject(1, order.getProductId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        order.setPThickness(rs.getObject("thickness", BigDecimal.class));
                        order.setPWidth(rs.getObject("width", BigDecimal.class));
                        order.setPDensity(rs.getObject("density", BigDecimal.class));
                        order.setPGramWeight(rs.getObject("gram_weight", BigDecimal.class));
                        order.setPMaterial(rs.getString("material"));
                    }
                }
            } catch (SQLException e) {
                log.warn("print data loader lookup failed (product spec)", e);
            }
        }
        // 注入 BOM 备注 (打印模板 {{bomRemark}})
        if (order != null && order.getBomId() != null) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT remark FROM prd_bom WHERE id = ? AND deleted = 0")) {
                ps.setObject(1, order.getBomId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) order.setBomRemark(rs.getString("remark"));
                }
            } catch (SQLException e) {
                log.warn("print data loader lookup failed (bom remark)", e);
            }
        }
        return order;
    }

    /** 查询商品的 spec 字段 (用于打印回退, 避免生产单快照为空) */
    public String findProductSpec(Long productId) {
        if (productId == null) return null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT spec FROM base_product WHERE id = ? AND deleted = 0")) {
            ps.setObject(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("spec");
            }
        } catch (SQLException e) {
            log.warn("print data loader lookup failed (product spec fallback)", e);
        }
        return null;
    }

    public PurReturn findPurReturn(Long id) {
        PurReturn r = purReturnMapper.selectById(id);
        if (r != null) {
            r.setDetails(purReturnDetailMapper.selectByReturnId(id));
            fillWarehouseName(r);
        }
        return r;
    }

    public SalReturn findSalReturn(Long id) {
        SalReturn r = salReturnMapper.selectById(id);
        if (r != null) {
            r.setDetails(salReturnDetailMapper.selectByReturnId(id));
            fillWarehouseName(r);
        }
        return r;
    }

    private void fillWarehouseName(PurReturn r) {
        if (r.getWarehouseId() == null) return;
        String sql = "SELECT warehouse_name FROM base_warehouse WHERE id = ? AND deleted = 0";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, r.getWarehouseId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) r.setWarehouseName(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("print data loader lookup failed (pur return warehouse)", e);
        }
    }

    private void fillWarehouseName(SalReturn r) {
        if (r.getWarehouseId() == null) return;
        String sql = "SELECT warehouse_name FROM base_warehouse WHERE id = ? AND deleted = 0";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, r.getWarehouseId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) r.setWarehouseName(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("print data loader lookup failed (sal return warehouse)", e);
        }
    }

    public List<PurReceiptDetail> findPurReceiptDetails(Long receiptId) {
        String sql = "SELECT d.*, p.thickness AS p_thickness, p.width AS p_width, p.density AS p_density, p.gram_weight AS p_gram_weight, p.material AS p_material, p.sales_price AS p_sales_price, p.model AS p_model " +
                     "FROM pur_receipt_detail d LEFT JOIN base_product p ON d.product_id = p.id " +
                     "WHERE d.deleted = 0 AND d.receipt_id = ? ORDER BY d.line_no";
        List<PurReceiptDetail> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, receiptId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowReceiptDetail(rs));
            }
            return list;
        } catch (SQLException e) {
            throw BizException.of("查询入库明细失败: " + e.getMessage());
        }
    }

    public List<SalDeliveryDetail> findSalDeliveryDetails(Long deliveryId) {
        String sql = "SELECT d.*, p.thickness AS p_thickness, p.width AS p_width, p.density AS p_density, p.gram_weight AS p_gram_weight, p.material AS p_material, p.sales_price AS p_sales_price, p.model AS p_model " +
                     "FROM sal_delivery_detail d LEFT JOIN base_product p ON d.product_id = p.id " +
                     "WHERE d.deleted = 0 AND d.delivery_id = ? ORDER BY d.line_no";
        List<SalDeliveryDetail> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowDeliveryDetail(rs));
            }
            return list;
        } catch (SQLException e) {
            throw BizException.of("查询出库明细失败: " + e.getMessage());
        }
    }

    // ========== ResultSet -> Entity ==========

    private PurReceipt mapRowReceipt(ResultSet rs) throws SQLException {
        PurReceipt r = new PurReceipt();
        r.setId(rs.getObject("id", Long.class));
        r.setBillNo(rs.getString("bill_no"));
        r.setBillDate(getLocalDate(rs, "bill_date"));
        r.setOrderId(rs.getObject("order_id", Long.class));
        r.setOrderNo(rs.getString("order_no"));
        r.setSupplierId(rs.getObject("supplier_id", Long.class));
        r.setSupplierName(rs.getString("supplier_name"));
        r.setWarehouseId(rs.getObject("warehouse_id", Long.class));
        r.setWarehouseName(rs.getString("_warehouse_name"));
        r.setAreaId(rs.getObject("area_id", Long.class));
        r.setAreaName(rs.getString("_area_name"));
        r.setBuyerId(rs.getObject("buyer_id", Long.class));
        r.setBillType(rs.getString("bill_type"));
        r.setTotalQty(getBigDecimal(rs, "total_qty"));
        r.setTotalAmount(getBigDecimal(rs, "total_amount"));
        r.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        r.setTotalAmountTax(getBigDecimal(rs, "total_amount_tax"));
        r.setPaidAmount(getBigDecimal(rs, "paid_amount"));
        r.setPayType(rs.getString("pay_type"));
        r.setBillStatus(rs.getString("bill_status"));
        r.setDeliveryNo(rs.getString("delivery_no"));
        r.setRemark(rs.getString("remark"));
        r.setCreateBy(rs.getObject("create_by", Long.class));
        r.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        r.setUpdateBy(rs.getObject("update_by", Long.class));
        r.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return r;
    }

    private SalDelivery mapRowDelivery(ResultSet rs) throws SQLException {
        SalDelivery r = new SalDelivery();
        r.setId(rs.getObject("id", Long.class));
        r.setBillNo(rs.getString("bill_no"));
        r.setBillDate(getLocalDate(rs, "bill_date"));
        r.setOrderId(rs.getObject("order_id", Long.class));
        r.setOrderNo(rs.getString("order_no"));
        r.setCustomerId(rs.getObject("customer_id", Long.class));
        r.setCustomerName(rs.getString("customer_name"));
        r.setWarehouseId(rs.getObject("warehouse_id", Long.class));
        // 通过 JOIN 取出 warehouse_name / area_name, 模板中 {{warehouseName}} / {{areaName}} 可用
        r.setWarehouseName(rs.getString("_warehouse_name"));
        r.setAreaId(rs.getObject("area_id", Long.class));
        r.setAreaName(rs.getString("_area_name"));
        r.setSalesmanId(rs.getObject("salesman_id", Long.class));
        r.setSalesmanName(rs.getString("salesman_name"));
        r.setBillType(rs.getString("bill_type"));
        r.setTotalQty(getBigDecimal(rs, "total_qty"));
        r.setTotalAmount(getBigDecimal(rs, "total_amount"));
        r.setDiscountAmount(getBigDecimal(rs, "discount_amount"));
        r.setTailAmount(getBigDecimal(rs, "tail_amount"));
        r.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        r.setTotalAmountTax(getBigDecimal(rs, "total_amount_tax"));
        r.setReceivedAmount(getBigDecimal(rs, "received_amount"));
        r.setCostAmount(getBigDecimal(rs, "cost_amount"));
        r.setProfitAmount(getBigDecimal(rs, "profit_amount"));
        r.setDeliveryDate(getLocalDate(rs, "delivery_date"));
        r.setAddress(rs.getString("address"));
        r.setPhone(rs.getString("phone"));
        r.setBillStatus(rs.getString("bill_status"));
        r.setRemark(rs.getString("remark"));
        r.setCreateBy(rs.getObject("create_by", Long.class));
        r.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        r.setUpdateBy(rs.getObject("update_by", Long.class));
        r.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return r;
    }

    private PurReceiptDetail mapRowReceiptDetail(ResultSet rs) throws SQLException {
        PurReceiptDetail d = new PurReceiptDetail();
        d.setId(rs.getObject("id", Long.class));
        d.setReceiptId(rs.getObject("receipt_id", Long.class));
        d.setLineNo(rs.getObject("line_no", Integer.class));
        d.setOrderDetailId(rs.getObject("order_detail_id", Long.class));
        d.setProductId(rs.getObject("product_id", Long.class));
        d.setProductCode(rs.getString("product_code"));
        d.setProductName(rs.getString("product_name"));
        d.setSpec(rs.getString("spec"));
        d.setUnitId(rs.getObject("unit_id", Long.class));
        d.setUnitName(rs.getString("unit_name"));
        d.setQty(getBigDecimal(rs, "qty"));
        d.setPrice(getBigDecimal(rs, "price"));
        d.setAmount(getBigDecimal(rs, "amount"));
        d.setTaxRate(getBigDecimal(rs, "tax_rate"));
        d.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        d.setAmountTax(getBigDecimal(rs, "amount_tax"));
        d.setBatchNo(rs.getString("batch_no"));
        d.setLocationId(rs.getObject("location_id", Long.class));
        d.setLocationName(rs.getString("location_name"));
        d.setSnNo(rs.getString("sn_no"));
        // 商品规格属性 (JOIN 注入)
        d.setPThickness(getBigDecimal(rs, "p_thickness"));
        d.setPWidth(getBigDecimal(rs, "p_width"));
        d.setPDensity(getBigDecimal(rs, "p_density"));
        d.setPGramWeight(getBigDecimal(rs, "p_gram_weight"));
        d.setPMaterial(rs.getString("p_material"));
        d.setPModel(rs.getString("p_model"));
        return d;
    }

    private SalDeliveryDetail mapRowDeliveryDetail(ResultSet rs) throws SQLException {
        SalDeliveryDetail d = new SalDeliveryDetail();
        d.setId(rs.getObject("id", Long.class));
        d.setDeliveryId(rs.getObject("delivery_id", Long.class));
        d.setLineNo(rs.getObject("line_no", Integer.class));
        d.setOrderDetailId(rs.getObject("order_detail_id", Long.class));
        d.setProductId(rs.getObject("product_id", Long.class));
        d.setProductCode(rs.getString("product_code"));
        d.setProductName(rs.getString("product_name"));
        d.setSpec(rs.getString("spec"));
        d.setUnitId(rs.getObject("unit_id", Long.class));
        d.setUnitName(rs.getString("unit_name"));
        d.setQty(getBigDecimal(rs, "qty"));
        d.setPrice(getBigDecimal(rs, "price"));
        d.setAmount(getBigDecimal(rs, "amount"));
        d.setTaxRate(getBigDecimal(rs, "tax_rate"));
        d.setTaxAmount(getBigDecimal(rs, "tax_amount"));
        d.setAmountTax(getBigDecimal(rs, "amount_tax"));
        d.setBatchNo(rs.getString("batch_no"));
        d.setLocationId(rs.getObject("location_id", Long.class));
        d.setLocationName(rs.getString("location_name"));
        d.setSnNo(rs.getString("sn_no"));
        d.setRemark(rs.getString("remark"));
        // 商品规格属性 (JOIN 注入)
        d.setPThickness(getBigDecimal(rs, "p_thickness"));
        d.setPWidth(getBigDecimal(rs, "p_width"));
        d.setPDensity(getBigDecimal(rs, "p_density"));
        d.setPGramWeight(getBigDecimal(rs, "p_gram_weight"));
        d.setPMaterial(rs.getString("p_material"));
        d.setPModel(rs.getString("p_model"));
        return d;
    }

    private LocalDate getLocalDate(ResultSet rs, String col) {
        try { return rs.getObject(col, LocalDate.class); } catch (Exception e) { return null; }
    }

    private BigDecimal getBigDecimal(ResultSet rs, String col) {
        try { return rs.getObject(col, BigDecimal.class); } catch (Exception e) { return null; }
    }
}
