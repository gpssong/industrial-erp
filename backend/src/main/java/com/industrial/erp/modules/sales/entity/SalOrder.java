package com.industrial.erp.modules.sales.entity;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@TableName("sal_order")
public class SalOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String billNo;
    private LocalDate billDate;
    private Long customerId;
    private String customerName;
    private String priceLevel;
    private Long warehouseId;
    private Long salesmanId;
    private String salesmanName;
    private String orderType;
    private String payType;
    private String currency;
    private BigDecimal exchangeRate;
    private BigDecimal totalQty;
    private BigDecimal totalAmount;
    private BigDecimal discountRate;
    private BigDecimal discountAmount;
    private BigDecimal tailAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmountTax;
    private BigDecimal receivedAmount;
    private LocalDate deliveryDate;
    private String address;
    private String phone;
    private String billStatus;
    private Long sourceBillId;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;

    private List<SalOrderDetail> details;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getPriceLevel() { return priceLevel; }
    public void setPriceLevel(String priceLevel) { this.priceLevel = priceLevel; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getSalesmanId() { return salesmanId; }
    public void setSalesmanId(Long salesmanId) { this.salesmanId = salesmanId; }
    public String getSalesmanName() { return salesmanName; }
    public void setSalesmanName(String salesmanName) { this.salesmanName = salesmanName; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getPayType() { return payType; }
    public void setPayType(String payType) { this.payType = payType; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public void setDiscountRate(BigDecimal discountRate) { this.discountRate = discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getTailAmount() { return tailAmount; }
    public void setTailAmount(BigDecimal tailAmount) { this.tailAmount = tailAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getTotalAmountTax() { return totalAmountTax; }
    public void setTotalAmountTax(BigDecimal totalAmountTax) { this.totalAmountTax = totalAmountTax; }
    public BigDecimal getReceivedAmount() { return receivedAmount; }
    public void setReceivedAmount(BigDecimal receivedAmount) { this.receivedAmount = receivedAmount; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getBillStatus() { return billStatus; }
    public void setBillStatus(String billStatus) { this.billStatus = billStatus; }
    public Long getSourceBillId() { return sourceBillId; }
    public void setSourceBillId(Long sourceBillId) { this.sourceBillId = sourceBillId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public List<SalOrderDetail> getDetails() { return details; }
    public void setDetails(List<SalOrderDetail> details) { this.details = details; }
}
