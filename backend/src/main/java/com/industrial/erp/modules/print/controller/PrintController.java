package com.industrial.erp.modules.print.controller;

import com.industrial.erp.common.R;
import com.industrial.erp.modules.print.service.PrintService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "单据打印")
@RestController
@RequestMapping("/print")
public class PrintController {

    public PrintController(PrintService printService) {
        this.printService = printService;
    }

    private final PrintService printService;

    @GetMapping(value = "/sales-delivery/{id}.html", produces = "text/html;charset=UTF-8")
    public String salesDelivery(@PathVariable Long id) {
        return printService.renderSalesDelivery(id);
    }

    @GetMapping(value = "/purchase-receipt/{id}.html", produces = "text/html;charset=UTF-8")
    public String purchaseReceipt(@PathVariable Long id) {
        return printService.renderPurchaseReceipt(id);
    }

    @GetMapping(value = "/prd-order/{id}.html", produces = "text/html;charset=UTF-8")
    public String prdOrder(@PathVariable Long id) {
        return printService.renderPrdOrder(id);
    }

    @GetMapping(value = "/purchase-return/{id}.html", produces = "text/html;charset=UTF-8")
    public String purchaseReturn(@PathVariable Long id) {
        return printService.renderPurReturn(id);
    }

    @GetMapping(value = "/sales-return/{id}.html", produces = "text/html;charset=UTF-8")
    public String salesReturn(@PathVariable Long id) {
        return printService.renderSalReturn(id);
    }

}
