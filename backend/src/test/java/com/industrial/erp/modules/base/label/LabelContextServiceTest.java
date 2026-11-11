package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.context.LabelContextLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelContextServiceTest {

    @Mock private LabelContextLoader fgRollLoader;
    @Mock private LabelMasterService masterService;

    private LabelMaster master() {
        LabelMaster m = new LabelMaster();
        m.setProducerName("华塑智造工厂");
        m.setProducerAddress("杭州市萧山区工业路 88 号");
        m.setXkNo("XK16-001-00123");
        m.setExecStandard("Q/ZPC 1-2017");
        m.setStorageCondition("阴凉干燥处存放");
        m.setInspectorCode("JY-01");
        return m;
    }

    @Test
    @DisplayName("装配漏挂一个 loader → 400 点名「还没有数据加载器」，不是 NPE/500")
    void certWithoutLoaderThrows400() {
        // CERT 在 v1.1.178 起是有真 loader 的；这里用它当「类型合法但本次没注册实现」的例子，
        // 是因为 service 只收了 FG_ROLL 一个 mock —— 钉的是路由分支，不是生产装配。
        when(fgRollLoader.bizType()).thenReturn(LabelBizType.FG_ROLL);
        LabelContextService service = new LabelContextService(List.of(fgRollLoader), masterService);

        assertThatThrownBy(() -> service.load("CERT", 1L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还没有数据加载器");
    }

    @Test
    @DisplayName("主数据 6 个键并进每一项，loader 自己的业务键一个不丢")
    void mergesMasterKeysIntoEveryRow() {
        when(fgRollLoader.bizType()).thenReturn(LabelBizType.FG_ROLL);
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("productName", "PE 平口袋");
        row1.put("qty", new BigDecimal("5000"));
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("productName", "PP 背心袋");
        row2.put("qty", new BigDecimal("800"));
        when(fgRollLoader.load(1L, null)).thenReturn(List.of(row1, row2));
        when(masterService.load()).thenReturn(master());

        List<Map<String, Object>> out = new LabelContextService(List.of(fgRollLoader), masterService)
                .load("FG_ROLL", 1L, null);

        assertThat(out).hasSize(2);
        for (Map<String, Object> m : out) {
            assertThat(m)
                    .containsEntry("producerName", "华塑智造工厂")
                    .containsEntry("producerAddress", "杭州市萧山区工业路 88 号")
                    .containsEntry("xkNo", "XK16-001-00123")
                    .containsEntry("execStandard", "Q/ZPC 1-2017")
                    .containsEntry("storageCondition", "阴凉干燥处存放")
                    .containsEntry("inspectorCode", "JY-01");
        }
        assertThat(out.get(0))
                .containsEntry("productName", "PE 平口袋")
                .containsEntry("qty", new BigDecimal("5000"));
        assertThat(out.get(1))
                .containsEntry("productName", "PP 背心袋")
                .containsEntry("qty", new BigDecimal("800"));
    }
}
