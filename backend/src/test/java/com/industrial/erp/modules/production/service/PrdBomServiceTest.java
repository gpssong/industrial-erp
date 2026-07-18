package com.industrial.erp.modules.production.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdBomDetail;
import com.industrial.erp.modules.production.mapper.PrdBomDetailMapper;
import com.industrial.erp.modules.production.mapper.PrdBomMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PrdBomService 单元测试.
 *
 * <p>覆盖核心场景:
 * <ol>
 *   <li>BOM 创建 → 明细正确保存, 自动生成单号</li>
 *   <li>BOM 更新 → 旧明细先删后插</li>
 *   <li>BOM 软删除 → 主表 + 子表级联</li>
 *   <li>成品引用数统计</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PrdBomService BOM 配方")
class PrdBomServiceTest {

    @Mock private PrdBomMapper bomMapper;
    @Mock private PrdBomDetailMapper detailMapper;
    @Mock private BaseProductMapper productMapper;
    @Mock private PermissionService permService;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private OperLogPublisher operLogPublisher;

    @InjectMocks private PrdBomService bomService;

    private static final Long BOM_ID = 1L;
    private static final Long PRODUCT_ID = 100L;

    // ========== 辅助方法 ==========

    private PrdBom mockBom() {
        PrdBom bom = new PrdBom();
        bom.setId(BOM_ID);
        bom.setBomCode("BOM-001");
        bom.setBomName("测试配方");
        bom.setLossRate(BigDecimal.valueOf(2));
        bom.setStatus(1);
        bom.setIsDefault(0);
        return bom;
    }

    private PrdBomDetail mockDetail(Long bomId, int lineNo) {
        PrdBomDetail d = new PrdBomDetail();
        d.setId((long) (1000 + lineNo));
        d.setBomId(bomId);
        d.setLineNo(lineNo);
        d.setProductId(PRODUCT_ID);
        d.setProductCode("P001");
        d.setProductName("原料-A");
        d.setBaseQty(BigDecimal.valueOf(100));
        d.setLossRate(BigDecimal.valueOf(1));
        return d;
    }

    // ========== 测试用例 ==========

    @Test
    @DisplayName("新增 BOM: 自动生成单号 + 保存主表 + 保存明细")
    void add_generatesBillNo_andPersistsDetails() {
        when(permService.requirePerm("production:bom:add")).thenReturn(null);
        when(billNoGenerator.generate("BOM")).thenReturn("BOM202607170001");

        PrdBom bom = new PrdBom();
        bom.setBomCode(""); // 空码 → 自动生成
        bom.setLossRate(BigDecimal.ZERO);
        bom.setIsDefault(0);
        bom.setStatus(1);
        List<PrdBomDetail> details = Arrays.asList(mockDetail(1L, 1), mockDetail(1L, 2));
        bom.setDetails(details);

        bomService.add(bom);

        // 主表插入
        ArgumentCaptor<PrdBom> bomCaptor = ArgumentCaptor.forClass(PrdBom.class);
        verify(bomMapper, times(1)).insert(bomCaptor.capture());
        PrdBom saved = bomCaptor.getValue();
        assertThat(saved.getBomCode()).isEqualTo("BOM202607170001");

        // 明细插入 (2 条)
        verify(detailMapper, times(2)).insert(any(PrdBomDetail.class));

        // 第一个明细: bomId=1, lineNo=1
        ArgumentCaptor<PrdBomDetail> detailCaptor = ArgumentCaptor.forClass(PrdBomDetail.class);
        verify(detailMapper, times(2)).insert(detailCaptor.capture());
        List<PrdBomDetail> captured = detailCaptor.getAllValues();
        assertThat(captured.get(0).getBomId()).isEqualTo(1L);
        assertThat(captured.get(0).getLineNo()).isEqualTo(1);
        assertThat(captured.get(1).getLineNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("新增 BOM: 用户传入 bomCode 时不覆盖")
    void add_preservesUserProvidedBomCode() {
        when(permService.requirePerm("production:bom:add")).thenReturn(null);

        PrdBom bom = new PrdBom();
        bom.setBomCode("CUSTOM-001");
        bom.setLossRate(BigDecimal.ZERO);
        bom.setIsDefault(0);
        bom.setStatus(1);
        bom.setDetails(Collections.emptyList());

        bomService.add(bom);

        ArgumentCaptor<PrdBom> captor = ArgumentCaptor.forClass(PrdBom.class);
        verify(bomMapper).insert(captor.capture());
        assertThat(captor.getValue().getBomCode()).isEqualTo("CUSTOM-001");
        verify(billNoGenerator, never()).generate(anyString());
    }

    @Test
    @DisplayName("更新 BOM: 先删旧明细, 再插新明细")
    void update_deletesOldAndInsertsNewDetails() {
        when(permService.requirePerm("production:bom:edit")).thenReturn(null);

        PrdBom bom = new PrdBom();
        bom.setId(BOM_ID);
        bom.setBomCode("BOM-001");
        bom.setBomName("更新后的配方");
        bom.setDetails(Arrays.asList(mockDetail(BOM_ID, 1)));

        when(bomMapper.selectById(BOM_ID)).thenReturn(mockBom());
        when(bomMapper.updateById(any())).thenReturn(1);

        bomService.update(bom);

        // 主表更新
        verify(bomMapper, times(1)).updateById(bom);
        // 先删除旧明细
        verify(detailMapper, times(1)).delete(any(LambdaQueryWrapper.class));
        // 再插入新明细
        verify(detailMapper, times(1)).insert(any(PrdBomDetail.class));
    }

    @Test
    @DisplayName("删除 BOM: 主表 + 明细软删除 + 操作日志")
    void delete_softDeletesBomAndDetails() {
        when(permService.requirePerm("production:bom:delete")).thenReturn(null);

        PrdBom bom = mockBom();
        when(bomMapper.selectById(BOM_ID)).thenReturn(bom);
        when(detailMapper.selectByBomId(BOM_ID)).thenReturn(Arrays.asList(mockDetail(BOM_ID, 1)));

        bomService.delete(BOM_ID);

        // 主表软删除
        verify(bomMapper, times(1))
                .update(eq(null), any(LambdaUpdateWrapper.class));
        // 明细软删除
        verify(detailMapper, times(1))
                .update(eq(null), any(LambdaUpdateWrapper.class));
        // 操作日志
        verify(operLogPublisher, times(1))
                .publishDeleteSnapshot(eq("BOM清单"), eq(String.valueOf(BOM_ID)), eq(bom), anyList());
    }

    @Test
    @DisplayName("删除 BOM: 不存在的 BOM 抛 BizException")
    void delete_throwsWhenNotFound() {
        when(permService.requirePerm("production:bom:delete")).thenReturn(null);
        when(bomMapper.selectById(BOM_ID)).thenReturn(null);

        assertThatThrownBy(() -> bomService.delete(BOM_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("BOM不存在");
    }

    @Test
    @DisplayName("统计: 成品引用数 - 有引用")
    void countProductsByBomId_returnsCount() {
        when(productMapper.selectCount(any())).thenReturn(3L);

        long count = bomService.countProductsByBomId(BOM_ID);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("统计: 成品引用数 - 无引用返回 0")
    void countProductsByBomId_returnsZero() {
        when(productMapper.selectCount(any())).thenReturn(0L);

        long count = bomService.countProductsByBomId(BOM_ID);
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("统计: null bomId 返回 0")
    void countProductsByBomId_nullReturnsZero() {
        assertThat(bomService.countProductsByBomId(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("批量统计: 多个 bomId 返回映射")
    void countProductsByBomIds_returnsMap() {
        BaseProduct p1 = new BaseProduct();
        p1.setBomId(1L);
        BaseProduct p2 = new BaseProduct();
        p2.setBomId(1L);
        BaseProduct p3 = new BaseProduct();
        p3.setBomId(2L);

        when(productMapper.selectList(any())).thenReturn(Arrays.asList(p1, p2, p3));

        Map<Long, Long> result = bomService.countProductsByBomIds(Arrays.asList(1L, 2L));

        assertThat(result).containsEntry(1L, 2L);
        assertThat(result).containsEntry(2L, 1L);
    }

    @Test
    @DisplayName("详情: 加载明细列表")
    void detail_loadsDetails() {
        PrdBom bom = mockBom();
        when(bomMapper.selectById(BOM_ID)).thenReturn(bom);
        when(detailMapper.selectByBomId(BOM_ID)).thenReturn(Arrays.asList(mockDetail(BOM_ID, 1)));

        PrdBom result = bomService.detail(BOM_ID);

        assertThat(result).isNotNull();
        assertThat(result.getDetails()).hasSize(1);
        assertThat(result.getDetails().get(0).getLineNo()).isEqualTo(1);
    }
}
