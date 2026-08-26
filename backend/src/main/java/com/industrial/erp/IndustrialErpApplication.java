package com.industrial.erp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 企业级工业进销存 ERP 系统启动入口
 */
@SpringBootApplication
@MapperScan("com.industrial.erp.modules.**.mapper")
@EnableAsync
@EnableScheduling
public class IndustrialErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndustrialErpApplication.class, args);
        log.info("""

                ███████╗██████╗ ██████╗     ███████╗████████╗ █████╗ ██████╗ ████████╗███████╗██████╗
                ██╔════╝██╔══██╗██╔══██╗    ██╔════╝╚══██╔══╝██╔══██╗██╔══██╗╚══██╔══╝██╔════╝██╔══██╗
                █████╗  ██████╔╝██████╔╝    ███████╗   ██║   ███████║██████╔╝   ██║   █████╗  ██║  ██║
                ██╔══╝  ██╔═══╝ ██╔══██╗    ╚════██║   ██║   ██╔══██║██╔══██╗   ██║   ██╔══╝  ██║  ██║
                ███████╗██║     ██║  ██║    ███████║   ██║   ██║  ██║██║  ██║   ██║   ███████╗██████╔╝
                ╚══════╝╚═╝     ╚═╝  ╚═╝    ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═════╝
                                                         IND U S T R I A L    E R P    S Y S T E M
                                                         :: Industrial ERP Started Success ::
                """);
    }
}
