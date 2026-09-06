package com.industrial.erp.modules.sales.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.19+: 销售订单公式单元测试 (含税单价口径, 订单不写 AR).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalOrderService 含税单价口径")
class SalOrderServiceTest {

    @Mock private SalOrderMapper orderMapper;
    @Mock private SalOrderDetailMapper detailMapper;
    @Mock private SalDeliveryDetailMapper deliveryDetailMapper;
    @Mock private BaseCustomerMapper customerMapper;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private PermissionService permService;
    @Mock private OperLogPublisher operLogPublisher;

    @InjectMocks private SalOrderService service;

    // v1.1.35-1: 初始化 SalOrder 实体 TableInfo + lambda cache,
    // 让 mock 环境下 LambdaUpdateWrapper.eq(SalOrder::getId, ...) 能解析字段
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, SalOrder.class);
    }

    @Test
    @DisplayName("add 默认税率13%: amount=200, totalAmount=totalAmountTax=200")
    void add_defaultTaxRate13() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L); c.setCustomerName("客户A"); c.setTaxRate(new BigDecimal("13.00"));
        lenient().when(customerMapper.selectById(50L)).thenReturn(c);

        lenient().doAnswer(inv -> {
            SalOrder arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(orderMapper).insert(any(SalOrder.class));

        SalOrder order = new SalOrder();
        order.setCustomerId(50L);
        SalOrderDetail d = new SalOrderDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        order.setDetails(Arrays.asList(d));

        service.add(order);

        SalOrderDetail detail = order.getDetails().get(0);
        assertThat(detail.getAmount()).isEqualByComparingTo("200.0000");
        assertThat(detail.getTaxAmount()).isEqualByComparingTo("0.0000");
        assertThat(detail.getAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("add 客户税率为0: amount=200 不变, taxAmount=0")
    void add_customerTaxRate0() {
        BaseCustomer c = new BaseCustomer();
        c.setId(50L); c.setCustomerName("客户A"); c.setTaxRate(BigDecimal.ZERO);
        lenient().when(customerMapper.selectById(50L)).thenReturn(c);
        lenient().doAnswer(inv -> {
            SalOrder arg = inv.getArgument(0);
            arg.setId(System.nanoTime());
            return 1;
        }).when(orderMapper).insert(any(SalOrder.class));

        SalOrder order = new SalOrder();
        order.setCustomerId(50L);
        SalOrderDetail d = new SalOrderDetail();
        d.setProductId(200L); d.setQty(new BigDecimal("2")); d.setPrice(new BigDecimal("100"));
        order.setDetails(Arrays.asList(d));

        service.add(order);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.0000");
        assertThat(order.getTotalAmountTax()).isEqualByComparingTo("200.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.0000");
    }

    // v1.1.35-1: 修复前 BUG = SalOrderMapper.xml 自定义 updateById 全字段 SET, check() 时 billNo 被覆盖成 NULL
    // 修复后: LambdaUpdateWrapper 只 SET billStatus, 其他字段不变
    // 测试策略: mock update mapper, 验证 update(entity, wrapper) 被调用了 1 次, 并且 wrapper 不为空 (Lambda 解析无误)

    @Test
    @DisplayName("check DRAFT→CHECKED: 走 LambdaUpdateWrapper.update 路径, 不再调用 updateById")
    void check_usesLambdaUpdateWrapper() {
        SalOrder existing = new SalOrder();
        existing.setId(100L);
        existing.setBillNo("SO202609060001");
        existing.setBillDate(LocalDate.of(2026, 9, 6));
        existing.setCustomerId(50L);
        existing.setCustomerName("客户A");
        existing.setBillStatus("DRAFT");
        when(orderMapper.selectById(100L)).thenReturn(existing);

        service.check(100L);

        // 修复后: 走 update(null, LambdaUpdateWrapper) 路径
        verify(orderMapper).update(eq(null), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        // 不能再调用 updateById (旧 BUG 路径)
        verify(orderMapper, org.mockito.Mockito.never()).updateById(any(SalOrder.class));
    }

    @Test
    @DisplayName("uncheck CHECKED→DRAFT: 同样走 LambdaUpdateWrapper, 不调用 updateById")
    void uncheck_usesLambdaUpdateWrapper() {
        SalOrder existing = new SalOrder();
        existing.setId(100L);
        existing.setBillNo("SO202609060001");
        existing.setBillStatus("CHECKED");
        when(orderMapper.selectById(100L)).thenReturn(existing);

        service.uncheck(100L);

        verify(orderMapper).update(eq(null), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        verify(orderMapper, org.mockito.Mockito.never()).updateById(any(SalOrder.class));
    }

    @Test
    @DisplayName("check 非 DRAFT 状态抛 BizException, 不写库")
    void check_wrongStatus_throws() {
        SalOrder existing = new SalOrder();
        existing.setId(100L);
        existing.setBillStatus("CHECKED");  // 已经是审核态
        when(orderMapper.selectById(100L)).thenReturn(existing);

        assertThatThrownBy(() -> service.check(100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草稿状态可审核");
        verify(orderMapper, org.mockito.Mockito.never()).update(any(), any());
    }
}