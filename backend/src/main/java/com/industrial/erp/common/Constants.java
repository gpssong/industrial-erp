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
