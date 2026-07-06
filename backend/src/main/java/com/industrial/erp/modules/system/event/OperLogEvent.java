package com.industrial.erp.modules.system.event;

import com.industrial.erp.modules.system.entity.SysOperLog;
import org.springframework.context.ApplicationEvent;

/**
 * 操作日志事件 (异步写库, 不阻塞业务)
 */
public class OperLogEvent extends ApplicationEvent {

    private final SysOperLog log;

    public OperLogEvent(Object source, SysOperLog log) {
        super(source);
        this.log = log;
    }

    public SysOperLog getLog() {
        return log;
    }
}
