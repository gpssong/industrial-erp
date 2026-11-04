package com.industrial.erp.modules.base.label;

import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelMasterServiceTest {

    @Mock private SysConfigService configService;
    @Mock private SysUserMapper userMapper;

    @Test
    @DisplayName("LABEL_MASTER 的五个键全部读出，JSON 坏了降级成空串而不是抛错")
    void readsFiveKeys() {
        when(configService.getByKey("LABEL_MASTER")).thenReturn(
                "{\"producerName\":\"舟山市鹏程包装有限公司\",\"producerAddress\":\"浙江省舟山市舟山高新技术产业园区大成九路32号\","
              + "\"xkNo\":\"浙 XK16-204-01155\",\"execStandard\":\"Q/ZPC 1-2017\",\"storageCondition\":\"储存在清洁、干燥、通风的库房内\"}");

        LabelMaster m = new LabelMasterService(configService, userMapper).load();

        assertThat(m.getProducerName()).isEqualTo("舟山市鹏程包装有限公司");
        assertThat(m.getXkNo()).isEqualTo("浙 XK16-204-01155");
        assertThat(m.getExecStandard()).isEqualTo("Q/ZPC 1-2017");
        assertThat(m.getProducerAddress()).startsWith("浙江省舟山市");
        assertThat(m.getStorageCondition()).startsWith("储存在清洁");
    }

    @Test
    @DisplayName("配置缺失/JSON 非法 → 各字段空串（不阻断打印，docs/32 §5）")
    void degradesToBlank() {
        when(configService.getByKey("LABEL_MASTER")).thenReturn("{这不是 json");
        LabelMaster m = new LabelMasterService(configService, userMapper).load();
        assertThat(m.getProducerName()).isEmpty();
        assertThat(m.getXkNo()).isEmpty();
    }

    @Test
    @DisplayName("未登录时 inspectorCode 是空串")
    void blankInspectorWhenNoSession() {
        when(configService.getByKey("LABEL_MASTER")).thenReturn("{}");
        LabelMaster m = new LabelMasterService(configService, userMapper).load();
        assertThat(m.getInspectorCode()).isEmpty();
    }
}
