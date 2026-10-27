package com.industrial.erp.modules.sales.service;

import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.common.Constants;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.mapper.InvLedgerMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockMapper;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售出库单乐观锁测试.
 *
 * <p>验证:
 * <ul>
 *   <li>version 字段带 {@link Version} 注解, MyBatis-Plus 才会在 UPDATE 时带上版本条件</li>
 *   <li>update() 使用客户端版本号下发, 不吞掉并发冲突异常</li>
 *   <li>不同单据的更新互不干扰</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalDeliveryVersionLockTest {

    @Mock private SalDeliveryMapper salDeliveryMapper;
    @Mock private SalDeliveryDetailMapper detailMapper;
    @Mock private BaseCustomerMapper customerMapper;
    @Mock private BaseWarehouseMapper warehouseMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private StockService stockService;
    @Mock private FinArapService arapService;
    @Mock private PermissionService permService;
    @Mock private SalOrderDetailMapper orderDetailMapper;
    @Mock private OperLogPublisher operLogPublisher;
    @Mock private BaseProductMapper productMapper;
    @Mock private SalOrderMapper orderMapper;
    @Mock private MesReportService mesReportService;
    @Mock private InvStockMapper inventoryStockMapper;
    @Mock private InvLedgerMapper ledgerMapper;

    @InjectMocks private SalDeliveryService salDeliveryService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, SalDelivery.class);
        TableInfoHelper.initTableInfo(assistant, SalDeliveryDetail.class);
    }

    @BeforeEach
    void setUp() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L);
        c.setCustomerName("客户A");
        c.setTaxRate(new BigDecimal("13.00"));
        lenient().when(customerMapper.selectById(anyLong())).thenReturn(c);

        BaseWarehouse w = new BaseWarehouse();
        w.setId(1L);
        w.setWarehouseName("主仓");
        lenient().when(warehouseMapper.selectById(anyLong())).thenReturn(w);

        lenient().doNothing().when(permService).requirePerm(anyString());
    }

    @Test
    @DisplayName("更新新单据应成功, 且按客户端版本号下发")
    void testNewDeliveryUpdateSuccess() {
        SalDelivery delivery = buildDelivery(1L, 0);
        when(salDeliveryMapper.selectById(1L)).thenReturn(buildDelivery(1L, 0));

        salDeliveryService.update(delivery);

        ArgumentCaptor<SalDelivery> captor = ArgumentCaptor.forClass(SalDelivery.class);
        verify(salDeliveryMapper, times(1)).updateById(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("并发冲突: mapper 抛乐观锁异常时不被吞掉")
    void testConcurrentUpdateShouldFail() {
        SalDelivery delivery = buildDelivery(1L, 0);
        when(salDeliveryMapper.selectById(1L)).thenReturn(buildDelivery(1L, 0));
        when(salDeliveryMapper.updateById(any(SalDelivery.class)))
                .thenThrow(new OptimisticLockingFailureException("version conflict"));

        assertThatThrownBy(() -> salDeliveryService.update(delivery))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("并发更新不同单据应互不干扰")
    void testConcurrentDifferentDeliveriesShouldSucceed() {
        SalDelivery d1 = buildDelivery(1L, 0);
        SalDelivery d2 = buildDelivery(2L, 0);
        when(salDeliveryMapper.selectById(1L)).thenReturn(buildDelivery(1L, 0));
        when(salDeliveryMapper.selectById(2L)).thenReturn(buildDelivery(2L, 0));

        salDeliveryService.update(d1);
        salDeliveryService.update(d2);

        verify(salDeliveryMapper, times(2)).updateById(any(SalDelivery.class));
    }

    @Test
    @DisplayName("@Version 注解应正确标记 version 字段")
    void testVersionAnnotationPresent() throws NoSuchFieldException {
        java.lang.reflect.Field versionField = SalDelivery.class.getDeclaredField("version");
        assertThat(versionField.isAnnotationPresent(Version.class))
                .as("version 字段应该有 @Version 注解")
                .isTrue();
    }

    private SalDelivery buildDelivery(Long id, int version) {
        SalDelivery delivery = new SalDelivery();
        delivery.setId(id);
        delivery.setBillNo("SD2024010100" + id);
        delivery.setBillStatus(Constants.STATUS_DRAFT);
        delivery.setCustomerId(50L);
        delivery.setWarehouseId(1L);
        delivery.setTotalAmount(new BigDecimal("100.00"));
        delivery.setVersion(version);

        SalDeliveryDetail detail = new SalDeliveryDetail();
        detail.setProductId(200L);
        detail.setQty(new BigDecimal("2"));
        detail.setPrice(new BigDecimal("50"));
        delivery.setDetails(Collections.singletonList(detail));
        return delivery;
    }
}
