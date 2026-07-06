package com.industrial.erp.modules.system.aspect;

import com.industrial.erp.modules.system.entity.SysOperLog;
import com.industrial.erp.modules.system.event.OperLogEvent;
import com.industrial.erp.modules.system.mapper.SysOperLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步写 sys_oper_log 表 (解决原 @Async 自身调用不生效的问题)
 */
@Component
public class OperLogEventListener {

    private static final Logger log = LoggerFactory.getLogger(OperLogEventListener.class);

    private final SysOperLogMapper mapper;

    public OperLogEventListener(SysOperLogMapper mapper) {
        this.mapper = mapper;
    }

    @Async
    @EventListener
    public void onOperLog(OperLogEvent event) {
        try {
            SysOperLog l = event.getLog();
            if (l != null) mapper.insert(l);
        } catch (Exception e) {
            log.warn("异步写操作日志失败: {}", e.getMessage());
        }
    }
}
