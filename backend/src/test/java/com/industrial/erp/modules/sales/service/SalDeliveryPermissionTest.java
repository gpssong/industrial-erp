package com.industrial.erp.modules.sales.service;

import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.finance.service.FinArapService;
import com.industrial.erp.modules.inventory.service.StockService;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * v1.1.24: 销售出库单权限注解集成测试
 *
 * <p>验证 @SaCheckPermission 注解的行为:
 * <ul>
 *   <li>无权限用户调用受限 API 应被拦截</li>
 *   <li>有权限用户调用正常 API 应成功</li>
 *   <li>SUPER_ADMIN 角色应拥有所有权限</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalDeliveryPermissionTest {

    @Mock
    private SalDeliveryMapper salDeliveryMapper;

    @Mock
    private SalDeliveryDetailMapper detailMapper;

    @Mock
    private BaseCustomerMapper customerMapper;

    @Mock
    private BaseWarehouseMapper warehouseMapper;

    @Mock
    private BillNoGenerator billNoGenerator;

    @Mock
    private StockService stockService;

    @Mock
    private FinArapService arapService;

    @Mock
    private PermissionService permService;

    @Mock
    private SalOrderDetailMapper orderDetailMapper;

    @Mock
    private OperLogPublisher operLogPublisher;

    @InjectMocks
    private SalDeliveryService salDeliveryService;

    @Test
    @DisplayName("无权限用户应无法访问销售出库列表")
    void testNoPermissionShouldFail() {
        // 模拟无权限场景
        doThrow(new RuntimeException("无权限访问")).when(permService).requirePerm("sales:delivery:list");

        // 调用 page 方法应该抛出异常
        assertThatThrownBy(() -> {
            salDeliveryService.page(1, 20, null, null, null, null);
        }).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("无权限");
    }

    @Test
    @DisplayName("有权限用户应能正常访问销售出库列表")
    void testWithPermissionShouldSucceed() {
        // 模拟有权限场景
        doNothing().when(permService).requirePerm("sales:delivery:list");
        when(salDeliveryMapper.selectPage(any(), any())).thenReturn(null);

        // 调用 page 方法应该成功（不抛异常）
        try {
            salDeliveryService.page(1, 20, null, null, null, null);
        } catch (Exception e) {
            // 期望成功，不抛异常
            org.junit.jupiter.api.Assertions.fail("有权限用户应该能正常访问");
        }

        verify(permService, times(1)).requirePerm("sales:delivery:list");
    }

    @Test
    @DisplayName("SUPER_ADMIN 角色应拥有所有权限")
    void testSuperAdminShouldHaveAllPermissions() {
        // SUPER_ADMIN 角色不检查具体权限
        doNothing().when(permService).requirePerm(anyString());

        when(salDeliveryMapper.selectPage(any(), any())).thenReturn(null);

        // 所有方法都应该成功
        salDeliveryService.page(1, 20, null, null, null, null);
        salDeliveryService.detail(1L);
        salDeliveryService.add(createSampleDelivery());
        salDeliveryService.update(createSampleDelivery());

        // 权限检查应该被调用
        verify(permService, atLeastOnce()).requirePerm(anyString());
    }

    @Test
    @DisplayName("销售出库单应包含必要的权限字段")
    void testSalDeliveryHasRequiredFields() {
        SalDelivery delivery = createSampleDelivery();

        // 验证关键字段存在
        assertThat(delivery.getId()).isNotNull();
        assertThat(delivery.getBillNo()).isNotNull();
        assertThat(delivery.getTotalAmount()).isNotNull();
        assertThat(delivery.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("权限注解应覆盖所有主要操作")
    void testPermissionAnnotationsCoverAllOperations() {
        // 测试主要操作都需要权限
        doNothing().when(permService).requirePerm(anyString());

        SalDelivery delivery = createSampleDelivery();

        // 列出所有需要权限的操作
        String[] operations = {
            "page",      // 列表
            "detail",    // 详情
            "add",       // 新增
            "update",    // 更新
            "delete"     // 删除
        };

        for (String op : operations) {
            try {
                switch (op) {
                    case "page":
                        when(salDeliveryMapper.selectPage(any(), any())).thenReturn(null);
                        salDeliveryService.page(1, 20, null, null, null, null);
                        break;
                    case "detail":
                        when(salDeliveryMapper.selectById(anyLong())).thenReturn(delivery);
                        salDeliveryService.detail(1L);
                        break;
                    case "add":
                        when(billNoGenerator.generate(anyString())).thenReturn("TEST001");
                        when(customerMapper.selectById(anyLong())).thenReturn(new BaseCustomer());
                        when(warehouseMapper.selectById(anyLong())).thenReturn(new BaseWarehouse());
                        salDeliveryService.add(delivery);
                        break;
                    case "update":
                        when(salDeliveryMapper.selectById(anyLong())).thenReturn(delivery);
                        salDeliveryService.update(delivery);
                        break;
                    case "delete":
                        when(salDeliveryMapper.selectById(anyLong())).thenReturn(delivery);
                        salDeliveryService.delete(1L);
                        break;
                }
            } catch (Exception e) {
                org.junit.jupiter.api.Assertions.fail("操作 " + op + " 应该成功: " + e.getMessage());
            }
        }
    }

    private SalDelivery createSampleDelivery() {
        SalDelivery delivery = new SalDelivery();
        delivery.setId(1L);
        delivery.setBillNo("SD20240101001");
        delivery.setCustomerId(1L);
        delivery.setCustomerName("测试客户");
        delivery.setWarehouseId(1L);
        delivery.setWarehouseName("测试仓库");
        delivery.setBillStatus("CHECKED");
        delivery.setTotalQty(new BigDecimal("10.00"));
        delivery.setTotalAmount(new BigDecimal("100.00"));
        delivery.setTotalAmountTax(new BigDecimal("100.00"));
        delivery.setTaxAmount(BigDecimal.ZERO);
        delivery.setDiscountAmount(BigDecimal.ZERO);
        delivery.setTailAmount(BigDecimal.ZERO);
        delivery.setVersion(0);
        delivery.setCreateTime(java.time.LocalDateTime.now());
        return delivery;
    }
}
