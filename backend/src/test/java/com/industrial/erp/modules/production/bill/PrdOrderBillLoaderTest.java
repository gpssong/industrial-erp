package com.industrial.erp.modules.production.bill;

import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdBomMapper;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * v1.1.162: 生产单飞鹅打印的数据模型.
 *
 * <p>其他 6 个 BillLoader 的主表根变量都叫 bill, PC 模板编辑器的提示也教 ${bill.xxx};
 * 生产单只给了 order, 于是按提示写的模板在 classicCompatible 下静默渲染成空白.
 * 这里锁住 order / bill 双别名 + details 根变量.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrdOrderBillLoaderTest {

    @Mock private PrdOrderMapper orderMapper;
    @Mock private PrdRequisitionDetailMapper reqDetailMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private PrdBomMapper bomMapper;

    @Test
    @DisplayName("模型同时提供 order 与 bill 两个主表别名, 明细在 details 根变量")
    void exposesOrderAndBillAliases() {
        PrdOrder order = new PrdOrder();
        order.setId(100L);
        order.setBillNo("PD202611030001");
        List<PrdRequisitionDetail> details = List.of(new PrdRequisitionDetail());
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(reqDetailMapper.selectByPrdOrderId(100L)).thenReturn(details);

        Map<String, Object> model = new PrdOrderBillLoader(orderMapper, reqDetailMapper, productMapper, bomMapper)
                .load(100L);

        assertThat(model).containsKeys("order", "bill", "details");
        assertThat(model.get("bill")).isSameAs(model.get("order")).isSameAs(order);
        assertThat(model.get("details")).isSameAs(details);
    }
}
