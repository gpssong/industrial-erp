package com.industrial.erp.modules.base.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.security.SecurityContext;
import org.springframework.stereotype.Service;

/**
 * v1.1.167 标签主数据读取 (docs/32 §2.1)。
 *
 * <p>写走通用 {@code PUT /system/config/value}（权限沿用系统配置编辑），本服务只管读；
 * 不新建配置端点。JSON 坏了不抛错 —— 车间不能因为主数据没配就不出标签。
 */
@Service
public class LabelMasterService {

    public static final String CONFIG_KEY = "LABEL_MASTER";

    public LabelMasterService(SysConfigService configService, SysUserMapper userMapper) {
        this.configService = configService;
        this.userMapper = userMapper;
        this.objectMapper = new ObjectMapper();
    }

    private final SysConfigService configService;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public LabelMaster load() {
        LabelMaster m = new LabelMaster();
        String json = configService.getByKey(CONFIG_KEY);
        if (json != null && !json.isBlank()) {
            try {
                JsonNode n = objectMapper.readTree(json);
                m.setProducerName(n.path("producerName").asText(""));
                m.setProducerAddress(n.path("producerAddress").asText(""));
                m.setXkNo(n.path("xkNo").asText(""));
                m.setExecStandard(n.path("execStandard").asText(""));
                m.setStorageCondition(n.path("storageCondition").asText(""));
            } catch (Exception ignored) {
                // 坏 JSON ⇒ 五个字段全空串；预览会标红，不阻断打印
            }
        }
        Long userId = SecurityContext.getUserId();
        if (userId != null) {
            SysUser u = userMapper.selectById(userId);
            if (u != null) m.setInspectorCode(u.getInspectorCode());
        }
        return m;
    }
}
