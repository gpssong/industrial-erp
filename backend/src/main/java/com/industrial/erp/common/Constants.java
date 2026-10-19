package com.industrial.erp.common;

public final class Constants {
    private Constants() {}

    public static final String CURRENT_USER = "currentUser";
    public static final String CURRENT_TENANT = "currentTenant";
    public static final Long DEFAULT_TENANT = 1L;
    public static final Long SUPER_ADMIN_ID = 1L;

    // 单据前缀
    public static final String BILL_PO = "PO";
    public static final String BILL_RKP = "RKP";
    public static final String BILL_RT = "RT";
    public static final String BILL_INQ = "INQ";
    public static final String BILL_SO = "SO";
    public static final String BILL_CKP = "CKP";
    public static final String BILL_SRT = "SRT";
    public static final String BILL_QUO = "QUO";
    public static final String BILL_PD = "PD";
    public static final String BILL_RQ = "RQ";
    public static final String BILL_PFI = "PFI";
    public static final String BILL_PSI = "PSI";  // v1.1.98 半成品入库单号前缀
    public static final String BILL_OI = "OI";
    public static final String BILL_OPI = "OPI";
    public static final String BILL_OPF = "OPF";
    public static final String BILL_TR = "TR";
    public static final String BILL_CK = "CK";
    public static final String BILL_PL = "PL";
    public static final String BILL_CT = "CT";
    public static final String BILL_SK = "SK";
    public static final String BILL_FK = "FK";
    public static final String BILL_RC = "RC";
    // v1.1.19+: 发票单据前缀 (BILL_INV = "INV")
    public static final String BILL_INV = "INV";
    // v1.1.79+: 工作流模块单据前缀 (设备维保 / 安全检查 / 应急预案)
    public static final String BILL_WM = "WM";
    public static final String BILL_SIN = "SIN";
    public static final String BILL_EP = "EP";

    // v1.1.90+: 质量检验单据前缀 (QI)
    public static final String BILL_QI = "QI";

    // v1.1.90+: 质量检验 inspection_type
    public static final String INSPECTION_IQC = "IQC";
    public static final String INSPECTION_FAI = "FAI";
    public static final String INSPECTION_PAT = "PAT";
    public static final String INSPECTION_OQC = "OQC";
    public static final String INSPECTION_OQC_RETURN = "OQC_RETURN";

    // v1.1.90+: 检验结果 result
    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_HOLD = "HOLD";
    public static final String RESULT_PASS_DISPOSITION = "PASS_DISPOSITION";
    public static final String RESULT_FAIL_REJECT = "FAIL_REJECT";

    // v1.1.90+: batch status 复用 v1.1.80 常量 BATCH_FROZEN/SCRAP, 此处枚举补充
    // BATCH_TYPE_RAW/SEMI/FG 已在 v1.1.80 定义
    public static final String BATCH_FROZEN = "FROZEN";
    public static final String BATCH_SCRAP = "SCRAP";
    public static final String BATCH_ACTIVE = "ACTIVE";

    // v1.1.90+: 设备类型 (equipment_type)
    public static final String EQUIPMENT_TYPE_FILM_BLOWING = "FILM_BLOWING";
    public static final String EQUIPMENT_TYPE_SIDE_SEAL = "SIDE_SEAL";
    public static final String EQUIPMENT_TYPE_BAG_MAKING = "BAG_MAKING";
    public static final String EQUIPMENT_TYPE_PRINTING = "PRINTING";
    public static final String EQUIPMENT_TYPE_LAMINATING = "LAMINATING";
    public static final String EQUIPMENT_TYPE_SLITTING = "SLITTING";

    // v1.1.90+: SOP 状态 (mes_process_sop.status)
    public static final String SOP_DRAFT = "DRAFT";
    public static final String SOP_ACTIVE = "ACTIVE";
    public static final String SOP_DEPRECATED = "DEPRECATED";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CHECKED = "CHECKED";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_PRODUCING = "PRODUCING";
    public static final String STATUS_PICKING = "PICKING";
    public static final String STATUS_ADJUSTED = "ADJUSTED";
    public static final String STATUS_UNPAID = "UNPAID";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_PAID = "PAID";

    // === v1.1.80 MES — prd_process 工序实例 status (TINYINT, 见 PrdProcess 实体) ===
    /** 未开工 (开单展开后初始态) */
    public static final int PROC_STATUS_UNSTARTED = 0;
    /** 进行中 (已报过工未完成) */
    public static final int PROC_STATUS_IN_PROGRESS = 1;
    /** 完成 */
    public static final int PROC_STATUS_FINISHED = 2;
    /** 回退 (反报工) */
    public static final int PROC_STATUS_ROLLED_BACK = 3;

    // === v1.1.80 MES — mes_batch.batch_type ===
    public static final String BATCH_TYPE_RAW = "RAW";
    public static final String BATCH_TYPE_SEMI = "SEMI";
    public static final String BATCH_TYPE_FG = "FG";

    public static final String REDIS_BILL_NO = "erp:billno:";
    public static final String REDIS_PRINT_LOCK = "erp:print:lock:";
    public static final String REDIS_STOCK_LOCK = "erp:stock:lock:";
    public static final String REDIS_LOGIN_LIMIT = "erp:login:limit:";
    public static final String REDIS_DICT = "erp:dict:";
    public static final String REDIS_PERM = "erp:perm:";
    public static final String REDIS_CONFIG = "erp:config:";

    public static final String LEDGER_PUR_RECEIPT = "PUR_RECEIPT";
    public static final String LEDGER_PUR_RETURN = "PUR_RETURN";
    public static final String LEDGER_SAL_DELIVERY = "SAL_DELIVERY";
    public static final String LEDGER_SAL_RETURN = "SAL_RETURN";
    public static final String LEDGER_PROD_IN = "PROD_IN";
    public static final String LEDGER_PROD_OUT = "PROD_OUT";
    public static final String LEDGER_TRANSFER = "TRANSFER";
    public static final String LEDGER_CHECK = "CHECK";
    public static final String LEDGER_INIT = "INIT";
    public static final String LEDGER_CUT = "CUT";

    public static final int DIRECTION_IN = 1;
    public static final int DIRECTION_OUT = -1;
}
