package com.industrial.erp.modules.inventory.mapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1.49: 用 Testcontainers MySQL 真实环境跑 selectStockAll, 捕获 ONLY_FULL_GROUP_BY 类问题.
 *
 * <p>v1.1.32/45/47 反复修 SQL, 因为 H2 MODE=MySQL 模拟不出来 MySQL 8.0 严格模式.
 * 这个测试在真 MySQL 上跑, 把这类问题挡在 CI 阶段.
 *
 * <p>{@code @Tag("integration")} + surefire 配置:
 * <ul>
 *   <li>{@code mvn test} — 默认跳过 (本地开发不被容器启动阻塞)</li>
 *   <li>{@code mvn verify} — 跑 (CI 全跑)</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("mysql-test")
@Sql(scripts = {
    "classpath:sql/schema-test.sql",
    "classpath:sql/data-test.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvLedgerQueryMapperMysqlIT {

    @Autowired
    private InvLedgerQueryMapper mapper;

    /**
     * v1.1.47 修的 bug: 库存=0 但 safety_stock > 0 的产品必须能进预警.
     * 旧 SQL 用 s.qty > 0 过滤, 这类漏报.
     */
    @Test
    void selectStockAll_includesZeroStockProducts() {
        List<Map<String, Object>> list = mapper.selectStockAll();

        assertThat(list)
            .as("selectStockAll 应该返回 base_product LEFT JOIN inv_stock 的预警产品")
            .isNotNull();

        // product 1: safety=70000, qty=0 → 必须出现 (v1.1.47 修复点)
        assertThat(list)
            .filteredOn(m -> "4-06-003-0018".equals(m.get("product_code")))
            .as("库存=0 但 safety=70000 的产品必须进预警")
            .hasSize(1);

        // product 2: safety=1000, qty=500 → 出现
        assertThat(list)
            .filteredOn(m -> "4-06-003-0019".equals(m.get("product_code")))
            .as("库存 500 < 安全 1000 应该进预警")
            .hasSize(1);

        // product 4: safety=100, qty=200 → 不应出现 (库存充足)
        assertThat(list)
            .filteredOn(m -> "4-06-003-0021".equals(m.get("product_code")))
            .as("库存 200 > 安全 100 不应该进预警")
            .isEmpty();

        // product 3: status=0 → 不应出现 (被 p.status=1 过滤)
        assertThat(list)
            .filteredOn(m -> "4-06-003-0020".equals(m.get("product_code")))
            .as("status=0 的商品不应该进预警")
            .isEmpty();
    }

    /**
     * 验证 p_safety_stock 字段名 (来自 base_product, 跟 inv_stock.safety_stock 区分).
     * App/PC 前端读这个字段做安全库存显示.
     */
    @Test
    void selectStockAll_usesBaseProductSafetyStock() {
        List<Map<String, Object>> list = mapper.selectStockAll();

        Map<String, Object> product1 = list.stream()
            .filter(m -> "4-06-003-0018".equals(m.get("product_code")))
            .findFirst()
            .orElseThrow();

        // p_safety_stock 来自 base_product (70000), 不是 inv_stock.safety_stock (0)
        assertThat(product1)
            .containsKey("p_safety_stock")
            .containsEntry("p_safety_stock", 70000.0);

        // shortage = p_safety_stock - qty = 70000 - 0 = 70000
        assertThat(product1)
            .containsKey("shortage")
            .containsEntry("shortage", 70000.0);
    }

    /**
     * 验证按 shortage DESC 排序 (最缺的优先).
     */
    @Test
    void selectStockAll_orderedByShortageDesc() {
        List<Map<String, Object>> list = mapper.selectStockAll();

        assertThat(list).hasSize(2); // product 1 + product 2

        // product 1 shortage=70000 应该排第一
        assertThat(list.get(0))
            .containsEntry("product_code", "4-06-003-0018");
        // product 2 shortage=500 应该排第二
        assertThat(list.get(1))
            .containsEntry("product_code", "4-06-003-0019");
    }
}
