package com.industrial.erp.config;

import cn.hutool.core.util.StrUtil;
import com.industrial.erp.modules.system.entity.SysConfig;
import com.industrial.erp.modules.system.mapper.SysConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 启动时写入系统版本信息到 sys_config 表 (key=SYSTEM_VERSION_INFO).
 * <p>前端「系统参数」页读取后展示给运维/用户, 便于部署后核对版本.
 * <p>Order(HIGHEST) 确保在其他 ApplicationRunner 之前执行.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SystemVersionInitializer implements ApplicationRunner {

    private final SysConfigMapper configMapper;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.data.redis.host:}")
    private String redisHost;

    @Value("${spring.data.redis.port:0}")
    private int redisPort;

    @Value("${erp.version:1.0.9}")
    private String erpVersion;

    public SystemVersionInitializer(SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", erpVersion);
        info.put("startTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        info.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        info.put("profiles", activeProfile);
        // 解析 DB 类型 (jdbc:mysql: → MySQL)
        String db = "未知";
        if (StrUtil.isNotBlank(datasourceUrl)) {
            if (datasourceUrl.startsWith("jdbc:mysql:")) db = "MySQL";
            else if (datasourceUrl.startsWith("jdbc:postgresql:")) db = "PostgreSQL";
            else if (datasourceUrl.startsWith("jdbc:oracle:")) db = "Oracle";
            else if (datasourceUrl.startsWith("jdbc:sqlserver:")) db = "SQL Server";
        }
        info.put("db", db);
        info.put("redis", StrUtil.isNotBlank(redisHost) && redisPort > 0);

        String value = new ObjectMapper().writeValueAsString(info);

        // upsert 到 sys_config (key=SYSTEM_VERSION_INFO)
        LambdaQueryWrapper<SysConfig> w = new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "SYSTEM_VERSION_INFO");
        SysConfig existing = configMapper.selectOne(w);
        if (existing == null) {
            SysConfig c = new SysConfig();
            c.setConfigKey("SYSTEM_VERSION_INFO");
            c.setConfigValue(value);
            c.setConfigName("系统版本信息 (只读, 启动时自动写入)");
            c.setConfigType(1);
            c.setRemark("v1.0.9+ 自动维护, 不要手动修改");
            configMapper.insert(c);
        } else {
            existing.setConfigValue(value);
            configMapper.updateById(existing);
        }
    }
}
