package com.industrial.erp.modules.xk.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.em.service.EmMaintainOrderService;
import com.industrial.erp.modules.em.service.EquipmentService;
import com.industrial.erp.modules.mes.service.MesReportService;
import com.industrial.erp.modules.purchase.service.PurReceiptService;
import com.industrial.erp.modules.qms.service.QmsInspectionService;
import com.industrial.erp.modules.report.mapper.ReportMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.service.SysConfigService;
import com.industrial.erp.modules.workflow.service.WfDashboardService;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import com.industrial.erp.modules.xk.service.XkAuditService;
import com.industrial.erp.modules.xk.service.XkEnvCheckService;
import com.industrial.erp.modules.xk.service.XkSafetyCheckService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XkPrintPreviewBrandTest {

    private XkController controller(SysConfigService cfg) {
        return new XkController(
                mock(ReportMapper.class),
                mock(WfDashboardService.class),
                mock(WfMaintainRecordService.class),
                mock(EmMaintainOrderService.class),
                mock(EquipmentService.class),
                mock(MesReportService.class),
                mock(PurReceiptService.class),
                mock(QmsInspectionService.class),
                mock(XkSafetyCheckService.class),
                mock(XkAuditService.class),
                mock(XkEnvCheckService.class),
                cfg,
                mock(SysUserMapper.class),
                mock(BaseCustomerMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void printPreviewReturnsBrandFromConfig() {
        SysConfigService cfg = mock(SysConfigService.class);
        when(cfg.getByKey("XK_AUDIT_RETENTION_YEARS")).thenReturn("3");
        when(cfg.getByKey("SYS_NAME")).thenReturn("鹏程ERP");
        when(cfg.getByKey("SYS_EN_NAME")).thenReturn("PENGCHENG ERP");
        when(cfg.getByKey("SYS_FACTORY")).thenReturn("华东一厂");
        when(cfg.getByKey("SYS_LOGO")).thenReturn("data:image/png;base64,AAA");

        R<Map<String, Object>> r = controller(cfg).printPreview();

        assertThat(r.getCode()).isEqualTo(200);
        Map<String, Object> brand = (Map<String, Object>) r.getData().get("brand");
        assertThat(brand).isNotNull();
        assertThat(brand).containsEntry("name", "鹏程ERP");
        assertThat(brand).containsEntry("enName", "PENGCHENG ERP");
        assertThat(brand).containsEntry("factory", "华东一厂");
        assertThat(brand).containsEntry("logo", "data:image/png;base64,AAA");
    }

    @Test
    @SuppressWarnings("unchecked")
    void printPreviewBrandFallsBackWhenConfigMissing() {
        SysConfigService cfg = mock(SysConfigService.class);

        R<Map<String, Object>> r = controller(cfg).printPreview();

        Map<String, Object> brand = (Map<String, Object>) r.getData().get("brand");
        assertThat(brand).isNotNull();
        assertThat(brand).containsEntry("name", "工业ERP");
        assertThat((String) brand.get("logo")).isEmpty();
    }
}