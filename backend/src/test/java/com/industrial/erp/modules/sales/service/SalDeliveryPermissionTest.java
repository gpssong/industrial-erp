package com.industrial.erp.modules.sales.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售出库单权限守卫测试.
 *
 * <p>验证每个对外方法都会调用 {@link PermissionService#requirePerm(String)}:
 * 无权限时异常上抛, 有权限时业务正常执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalDeliveryPermissionTest {

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

    private static final Long CUSTOMER_ID = 50L;
    private static final Long WAREHOUSE_ID = 1L;
    private static final Long PRODUCT_ID = 200L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, SalDelivery.class);
        TableInfoHelper.initTableInfo(assistant, SalDeliveryDetail.class);
    }

    @BeforeEach
    void setUp() {
        BaseCustomer c = new BaseCustomer();
        c.setId(CUSTOMER_ID);
        c.setCustomerName("测试客户");
        c.setTaxRate(new BigDecimal("13.00"));
        lenient().when(customerMapper.selectById(anyLong())).thenReturn(c);

        BaseWarehouse w = new BaseWarehouse();
        w.setId(WAREHOUSE_ID);
        w.setWarehouseName("测试仓库");
        lenient().when(warehouseMapper.selectById(anyLong())).thenReturn(w);

        lenient().when(billNoGenerator.generate(anyString())).thenReturn("SD20240101001");
        lenient().when(salDeliveryMapper.selectPageWithProduct(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Page<>());
        lenient().doAnswer(inv -> {
            SalDelivery arg = inv.getArgument(0);
            arg.setId(1L);
            return 1;
        }).when(salDeliveryMapper).insert(any(SalDelivery.class));
        lenient().doNothing().when(permService).requirePerm(anyString());
    }

    @Test
    @DisplayName("无权限时列表查询被阻断")
    void testNoPermissionShouldFail() {
        doThrow(new RuntimeException("无权限访问"))
                .when(permService).requirePerm("sales:delivery:list");

        assertThatThrownBy(() -> salDeliveryService.page(1, 20, null, null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无权限");
    }

    @Test
    @DisplayName("有权限时列表查询正常返回")
    void testWithPermissionShouldSucceed() {
        doNothing().when(permService).requirePerm("sales:delivery:list");

        assertThatCode(() -> salDeliveryService.page(1, 20, null, null, null, null, null))
                .doesNotThrowAnyException();

        verify(permService).requirePerm("sales:delivery:list");
    }

    @Test
    @DisplayName("分页参数与筛选条件透传到 mapper")
    void testPagePassesFilters() {
        salDeliveryService.page(2, 50, "SD001", CUSTOMER_ID, "客户A", Constants.STATUS_DRAFT, "薄膜");

        verify(salDeliveryMapper).selectPageWithProduct(any(), any(), any(), any(), any(), any());
        verify(permService).requirePerm("sales:delivery:list");
    }

    @Test
    @DisplayName("SUPER_ADMIN 场景: 各操作均经过权限校验")
    void testSuperAdminShouldHaveAllPermissions() {
        assertThatCode(() -> salDeliveryService.page(1, 20, null, null, null, null, null))
                .doesNotThrowAnyException();

        when(salDeliveryMapper.selectById(1L)).thenReturn(createSampleDelivery(Constants.STATUS_DRAFT));
        salDeliveryService.detail(1L);

        verify(permService, org.mockito.Mockito.atLeastOnce()).requirePerm(anyString());
    }

    @Test
    @DisplayName("新增/修改/删除/详情/列表都要求权限且可正常执行")
    void testPermissionGuardsCoverAllOperations() {
        SalDelivery delivery = createSampleDelivery(Constants.STATUS_DRAFT);

        // page
        assertThatCode(() -> salDeliveryService.page(1, 20, null, null, null, null, null))
                .doesNotThrowAnyException();

        // detail
        when(salDeliveryMapper.selectById(1L)).thenReturn(delivery);
        assertThatCode(() -> salDeliveryService.detail(1L)).doesNotThrowAnyException();

        // add
        assertThatCode(() -> salDeliveryService.add(delivery)).doesNotThrowAnyException();

        // update
        when(salDeliveryMapper.selectById(1L)).thenReturn(createSampleDelivery(Constants.STATUS_DRAFT));
        assertThatCode(() -> salDeliveryService.update(delivery)).doesNotThrowAnyException();

        // delete
        when(salDeliveryMapper.selectById(1L)).thenReturn(createSampleDelivery(Constants.STATUS_DRAFT));
        assertThatCode(() -> salDeliveryService.delete(1L)).doesNotThrowAnyException();

        verify(permService).requirePerm("sales:delivery:list");
        verify(permService).requirePerm("sales:delivery:add");
        verify(permService).requirePerm("sales:delivery:edit");
        verify(permService).requirePerm("sales:delivery:delete");
    }

    @Test
    @DisplayName("销售出库单应包含乐观锁 version 字段")
    void testSalDeliveryHasRequiredFields() {
        SalDelivery delivery = createSampleDelivery(Constants.STATUS_DRAFT);
        assertThat(delivery.getId()).isNotNull();
        assertThat(delivery.getTotalAmount()).isNotNull();
        assertThat(delivery.getVersion()).isNotNull();
    }

    private SalDelivery createSampleDelivery(String status) {
        SalDelivery delivery = new SalDelivery();
        delivery.setId(1L);
        delivery.setBillNo("SD20240101001");
        delivery.setCustomerId(CUSTOMER_ID);
        delivery.setCustomerName("测试客户");
        delivery.setWarehouseId(WAREHOUSE_ID);
        delivery.setWarehouseName("测试仓库");
        delivery.setBillStatus(status);
        delivery.setTotalQty(new BigDecimal("10.00"));
        delivery.setTotalAmount(new BigDecimal("100.00"));
        delivery.setTotalAmountTax(new BigDecimal("100.00"));
        delivery.setTaxAmount(BigDecimal.ZERO);
        delivery.setDiscountAmount(BigDecimal.ZERO);
        delivery.setTailAmount(BigDecimal.ZERO);
        delivery.setVersion(0);

        SalDeliveryDetail detail = new SalDeliveryDetail();
        detail.setId(10L);
        detail.setProductId(PRODUCT_ID);
        detail.setQty(new BigDecimal("2"));
        detail.setPrice(new BigDecimal("50"));
        delivery.setDetails(Arrays.asList(detail));
        return delivery;
    }
}
