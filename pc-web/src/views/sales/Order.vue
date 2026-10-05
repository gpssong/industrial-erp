<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" placeholder="单号" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增</el-button>
        <!-- v1.1.66: 勾选 ≥2 个同客户订单 → 合并成一张出库单 -->
        <el-button v-if="userStore.hasPerm('sales:delivery:add')" type="success" :disabled="!selectedOrders.length"
          @click="onMergeGenerateDelivery">合并生成出库单 ({{ selectedOrders.length }})</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading"
        row-key="id" @selection-change="onSelectionChange">
        <!-- v1.1.66: 多选列 (仅已审核订单可参与合并, 未审核行置灰) -->
        <el-table-column type="selection" width="45" :selectable="row => row.billStatus==='CHECKED'" />
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="120" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <!-- v1.1.41: 已发货 / 未发货数量 -->
        <el-table-column label="已发/未发" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.billStatus==='CHECKED'" style="font-size:12px;color:#67c23a">{{ row.shippedQty || 0 }} / {{ (row.totalQty || 0) - (row.shippedQty || 0) }}</span>
            <span v-else style="font-size:12px;color:#909399">—</span>
          </template>
        </el-table-column>
        <!-- v1.1.19+: 含税单价口径, totalAmount = totalAmountTax = 开单金额, 只显示「金额」一列 -->
        <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        <el-table-column prop="createByName" label="操作员" width="100">
          <template #default="{ row }">{{ row.createByName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='DRAFT'?'info':'success'">{{ row.billStatus === 'DRAFT' ? '草稿' : '已审核' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="460" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:edit')" link type="primary" size="small" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:delete')" link type="danger" size="small" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:check')" link type="success" size="small" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:order:uncheck')" link type="warning" size="small" @click="onUncheck(row)">反审核</el-button>
            <!-- v1.1.35: 已审核订单一键生成销售出库 (走现有 salDeliveryApi.add) -->
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:delivery:add')" link type="primary" size="small" @click="onGenerateDelivery(row)">生成出库单</el-button>
            <!-- v1.1.38: 查看该订单已关联的出库单 (追溯) -->
            <el-button v-if="userStore.hasPerm('sales:delivery:list')" link type="info" size="small" @click="onViewLinkedDeliveries(row)">关联出库单</el-button>
            <!-- v1.1.41: 查看该订单发货详情 (已发/未发数量) -->
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:order:list')" link type="success" size="small" @click="onViewDeliverySummary(row)">发货详情</el-button>
            <!-- v1.1.36: 打印 (浏览器 + 飞鹅云, 与 Delivery.vue 同模式) -->
            <el-dropdown v-if="['DRAFT','CHECKED'].includes(row.billStatus)" trigger="click" @command="(cmd) => onPrintCommand(cmd, row)">
              <el-button link type="warning" size="small">
                打印<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="browser">浏览器打印</el-dropdown-item>
                  <el-dropdown-item command="feie-preview">飞鹅打印预览</el-dropdown-item>
                  <el-dropdown-item command="feie-print" divided>飞鹅云打印</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <!-- v1.1.75: AI 解读 -->
            <el-button link type="primary" size="small" @click="onAiAnalyze(row)">🤖 AI 解读</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑订单' : '新增订单'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="客户"><el-select v-model="form.customerId" placeholder="请选择客户" filterable style="width:100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库"><el-select v-model="form.warehouseId" placeholder="请选择仓库" style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="交货日期"><el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="付款方式"><el-select v-model="form.payType" style="width:100%">
            <el-option label="款到发货" value="PREPAY" />
            <el-option label="月结" value="MONTHLY" />
            <el-option label="货到付款" value="ARRIVAL" />
          </el-select></el-form-item></el-col>
          <!-- v1.1.37: 采购订单号 (客户 PO 号) -->
          <el-col :span="8"><el-form-item label="采购订单号"><el-input v-model="form.poNo" placeholder="客户PO号" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="交货方式"><el-select v-model="form.deliveryMethod" style="width:100%">
            <el-option label="送货" value="DELIVERY" />
            <el-option label="自提" value="PICKUP" />
            <el-option label="专车直送" value="DIRECT" />
          </el-select></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="16"><el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item></el-col>
          <!-- 留空占位保持列对齐 (无新字段时可删) -->
          <el-col :span="8"></el-col>
        </el-row>
        <!-- 商品明细 -->
        <el-form-item label="明细">
          <el-button size="small" type="primary" plain @click="addDetail">添加商品</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="商品" width="200">
              <template #default="{ row }">
                <el-select v-model="row.productId" placeholder="商品" filterable @change="onProductChange(row)">
                  <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="productCode" label="编码" width="120" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column label="数量" width="120">
              <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :step-strictly="false" size="small" /></template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :precision="4" :step-strictly="false" size="small" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }"><el-button link type="danger" @click="form.details.splice($index, 1)">删除</el-button></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.35: 由销售订单一键生成销售出库单 (轻量级弹窗, 自动带入订单客户/仓库/明细)
         v1.1.66: 支持多订单合并 → 一张出库单挂多订单明细, 标题显示全部源订单号 -->
    <el-dialog v-model="generateDialogVisible"
      :title="sourceOrders.length > 1
        ? '合并生成出库单 (源 ' + sourceOrders.map(o => o.billNo).join('、') + ')'
        : (sourceOrder ? '生成出库单 (源 ' + sourceOrder.billNo + ')' : '生成出库单')"
      width="1040px" destroy-on-close>
      <el-form :model="deliveryForm" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="单据日期">
            <el-date-picker v-model="deliveryForm.billDate" type="date"
              value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="客户">
            <el-input v-model="deliveryForm.customerName" disabled />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库">
            <el-select v-model="deliveryForm.warehouseId" filterable style="width:100%">
              <el-option v-for="w in warehouses" :key="w.id"
                :label="w.warehouseName" :value="w.id" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="收货地址">
            <el-input v-model="deliveryForm.address" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="收货电话">
            <el-input v-model="deliveryForm.phone" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="整单折扣">
            <el-input-number v-model="deliveryForm.discountAmount" :min="0" :step-strictly="false" />
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <!-- v1.1.40: 交货方式 (下拉可改, 默认来自源订单) -->
          <el-col :span="8"><el-form-item label="交货方式">
            <el-select v-model="deliveryForm.deliveryMethod" style="width:100%">
              <el-option label="送货" value="DELIVERY" />
              <el-option label="自提" value="PICKUP" />
              <el-option label="专车直送" value="DIRECT" />
            </el-select>
            <span style="color:#999;font-size:12px">源订单交货方式, 可修改</span>
          </el-form-item></el-col>
          <!-- v1.1.41: 采购订单号 (只读, 来自源订单) -->
          <el-col :span="8"><el-form-item label="采购订单号">
            <el-input :value="deliveryForm.poNo || '—'" disabled />
            <span style="color:#999;font-size:12px">源订单采购订单号, 自动写入明细</span>
          </el-form-item></el-col>
          <el-col :span="8"></el-col>
        </el-row>

        <el-form-item label="商品明细">
          <el-table :data="deliveryForm.details" size="small" border max-height="380">
            <el-table-column type="index" label="#" width="50" />
            <!-- v1.1.66: 来源订单号 (多订单合并时, 每行标注它来自哪张订单; 单订单时也显示, 便于追溯) -->
            <el-table-column prop="orderNo" label="来源订单号" width="150" show-overflow-tooltip />
            <el-table-column prop="productCode" label="编码" width="120" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column prop="spec" label="规格" width="120" />
            <el-table-column prop="unitName" label="单位" width="60" />
            <!-- v1.1.65: 订单数量 (只读, 全量) 与 数量 (可编辑, 默认剩余) 对照, 避免重复超发 -->
            <el-table-column label="订单数量" width="90" align="right">
              <template #default="{ row }"><span style="color:#909399">{{ row._orderQty != null ? Number(row._orderQty).toFixed(2).replace(/\.?0+$/, '') : '0' }}</span></template>
            </el-table-column>
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input v-model="row.qty" size="small" type="text" inputmode="decimal"
                  @blur="row.qty = normNum(row.qty)" placeholder="0" />
              </template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }">
                <el-input v-model="row.price" size="small" type="text" inputmode="decimal"
                  @blur="row.price = normNum(row.price)" placeholder="0" />
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">
                <span>{{ ((+row.qty || 0) * (+row.price || 0)).toString() }}</span>
              </template>
            </el-table-column>
            <el-table-column label="批次" width="160">
              <template #default="{ row }">
                <el-input v-model="row.batchNo" size="small" placeholder="可选" />
              </template>
            </el-table-column>
            <el-table-column label="库位" width="100">
              <template #default="{ row }">
                <el-input v-model="row.locationName" size="small" />
              </template>
            </el-table-column>
            <!-- v1.1.47: 采购订单号 (客户 PO 号, 从源订单明细自动带入, 用户可改) -->
            <el-table-column label="采购订单号" width="140">
              <template #default="{ row }"><el-input v-model="row.poNo" size="small" placeholder="可选" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="removeDeliveryDetail($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p style="color:#999;font-size:12px;margin-top:6px">
            <template v-if="sourceOrders.length > 1">
              合并自 {{ sourceOrders.length }} 张订单: 数量默认填入各行「订单全量 − 已审核出库累计」的剩余数量, 已发完的明细默认为 0; 如需补发/超发请手工调整. 各行「来源订单号」已写入, 审核时按行归属分别累计.
            </template>
            <template v-else>
              提示: 数量默认填入「订单全量 − 已审核出库累计」的剩余数量, 已发完的明细默认为 0; 如需补发/超发请手工调整. 源订单 {{ sourceOrder?.billNo || '-' }} 关联字段已自动写入.
            </template>
          </p>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="deliveryForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onConfirmGenerate" :loading="submitting">保存为草稿 (需审核后生效)</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.38: 查看该订单已关联的出库单 (追溯入口) -->
    <el-dialog v-model="linkedDeliveryDialogVisible"
      :title="linkedSourceOrder ? '关联出库单 (源 ' + linkedSourceOrder.billNo + ')' : '关联出库单'"
      width="960px" destroy-on-close>
      <div v-loading="linkedDeliveryLoading" style="min-height:120px;">
        <el-table :data="linkedDeliveries" size="small" border stripe v-if="!linkedDeliveryLoading">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="billNo" label="出库单号" width="180">
            <template #default="{ row }">
              <el-button link type="primary" size="small"
                @click="jumpToDelivery(row.id)">{{ row.billNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="billDate" label="日期" width="120" />
          <el-table-column prop="customerName" label="客户" width="140" />
          <el-table-column prop="warehouseName" label="仓库" width="100" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.billStatus==='DRAFT'?'info':'success'" size="small">
                {{ row.billStatus==='DRAFT'?'草稿':'已审核' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalQty" label="数量" width="80" align="right" />
          <el-table-column prop="totalAmount" label="金额" width="110" align="right">
            <template #default="{ row }">
              <span>¥{{ (row.totalAmount || 0).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createByName" label="操作员" width="90">
            <template #default="{ row }">{{ row.createByName || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" size="small"
                @click="jumpToDelivery(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!linkedDeliveryLoading && linkedDeliveries.length === 0"
          description="该订单暂无关联出库单" :image-size="80" />
      </div>
      <p style="color:#999;font-size:12px;margin-top:8px">
        提示: 点击单号或「查看」跳转到销售出库详情页. 源订单 {{ linkedSourceOrder?.billNo || '-' }}.
      </p>
      <template #footer>
        <el-button @click="linkedDeliveryDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="reloadLinkedDeliveries" :loading="linkedDeliveryLoading">刷新</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.41: 发货详情弹窗 (已发/未发数量) -->
    <el-dialog v-model="deliverySummaryDialogVisible"
      :title="deliverySummaryOrder ? '发货详情 (源 ' + deliverySummaryOrder.billNo + ')' : '发货详情'"
      width="800px" destroy-on-close>
      <div v-loading="deliverySummaryLoading" style="min-height:120px;">
        <el-table :data="deliverySummaryData" size="small" border stripe v-if="!deliverySummaryLoading">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="productCode" label="编码" width="120" />
          <el-table-column prop="productName" label="名称" />
          <el-table-column prop="spec" label="规格" width="120" />
          <el-table-column prop="unitName" label="单位" width="60" />
          <el-table-column label="订单数量" width="100" align="right">
            <template #default="{ row }">{{ row.qty != null ? Number(row.qty).toFixed(2).replace(/\.?0+$/, '') : '0' }}</template>
          </el-table-column>
          <el-table-column label="已发货" width="100" align="right">
            <template #default="{ row }"><span style="color:#67c23a">{{ row.shippedQty != null ? Number(row.shippedQty).toFixed(2).replace(/\.?0+$/, '') : '0' }}</span></template>
          </el-table-column>
          <el-table-column label="未发货" width="100" align="right">
            <template #default="{ row }">
              <span :style="{ color: Number(row.unshippedQty) > 0 ? '#e6a23c' : '#909399' }">
                {{ row.unshippedQty != null ? Number(row.unshippedQty).toFixed(2).replace(/\.?0+$/, '') : '0' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag v-if="Number(row.unshippedQty) <= 0" type="success" size="small">已发完</el-tag>
              <el-tag v-else type="warning" size="small">部分发货</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!deliverySummaryLoading && deliverySummaryData.length === 0"
          description="该订单暂无明细" :image-size="80" />
      </div>
      <p style="color:#999;font-size:12px;margin-top:8px">
        提示: 已发货数量来源于已审核出库单的明细 qty 累计. 源订单 {{ deliverySummaryOrder?.billNo || '-' }}.
      </p>
      <template #footer>
        <el-button @click="deliverySummaryDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="reloadDeliverySummary" :loading="deliverySummaryLoading">刷新</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.75 AI 解读弹窗 -->
    <el-dialog v-model="aiDialogVisible" title="🤖 AI 单据解读" width="720px" destroy-on-close>
      <div class="ai-meta" v-if="aiBillNo || aiModel">
        <span v-if="aiBillNo">单号: <b>{{ aiBillNo }}</b></span>
        <span v-if="aiModel">模型: {{ aiModel }}</span>
      </div>
      <div v-loading="aiLoading" class="ai-body">
        <pre class="ai-content">{{ aiContent || (aiLoading ? '正在分析中，请稍候…' : '') }}</pre>
      </div>
      <template #footer>
        <el-button @click="aiDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 飞鹅云打印预览弹窗 (v1.1.36) -->
    <el-dialog v-model="feiePreviewVisible" title="飞鹅云打印预览" width="560px" destroy-on-close>
      <div v-loading="feiePreviewLoading" style="min-height:200px;">
        <pre style="white-space:pre-wrap;font-family:SimSun,monospace;font-size:12px;background:#fafafa;padding:12px;border-radius:4px;max-height:500px;overflow:auto;">{{ feiePreviewHtml }}</pre>
      </div>
      <template #footer>
        <el-button @click="feiePreviewVisible=false">关闭</el-button>
        <el-button type="primary" :loading="feiePrinting" :disabled="!feiePreviewHtml" @click="feieConfirmPrint">确认打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { salOrderApi, salDeliveryApi, aiApi } from '@/api/sales'
import { useUserStore } from '@/store/user'
import { customerApi, warehouseApi, productApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { usePrint, BIZ_TYPES } from '@/composables/usePrint'
import { feiePrintApi } from '@/api/feie'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

// v1.1.33: el-input + inputmode=decimal 数字归一化 (与 Delivery.vue 同模式)
const normNum = (v) => {
  if (v == null || v === '') return 0
  const n = Number(String(v).replace(/,/g, ''))
  return isFinite(n) ? n : 0
}

// v1.1.40: 交货方式枚举值 ↔ 中文标签双向映射 (与后端 SalOrderService.mapDeliveryMethod 一致)
const DELIVERY_METHOD_MAP = { 'DELIVERY': '送货', 'PICKUP': '自提', 'DIRECT': '专车直送' }
const mapDeliveryMethod   = (code) => DELIVERY_METHOD_MAP[code] || code
const mapCodeFromLabel    = (label) => Object.entries(DELIVERY_METHOD_MAP).find(([, v]) => v === label)?.[0] || label

const userStore = useUserStore()
const router = useRouter()

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = ref({ id: null, customerId: null, warehouseId: null, deliveryDate: null, payType: 'PREPAY', remark: '', details: [] })
const customers = ref([])
const warehouses = ref([])
const products = ref([])
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

// v1.1.35: 由销售订单生成出库单的轻量级弹窗状态
const generateDialogVisible = ref(false)
const sourceOrder = ref(null)        // 源订单完整 SalOrder (单订单时)
const sourceOrders = ref([])         // v1.1.66: 合并多订单时选中的订单 (列表行, 仅含 customerId)
const generating = ref(false)        // 加载源订单中
const selectedOrders = ref([])       // v1.1.66: 列表多选 (合并生成出库单)
const deliveryForm = reactive({
  billDate: '', customerId: null, customerName: '',
  warehouseId: null, address: '', phone: '',
  discountAmount: 0, tailAmount: 0, remark: '',
  details: [],
  // v1.1.41: 交货方式 / 采购订单号 (从源订单自动带入)
  deliveryMethod: '', poNo: '',
  // 联动字段 (后端 BaseMapper.insert 自动写入 sal_delivery.order_id/order_no)
  orderId: null, orderNo: ''
})

// v1.1.38: 关联出库单弹窗状态
const linkedDeliveryDialogVisible = ref(false)
const linkedSourceOrder = ref(null)        // 源订单 (显示标题)
const linkedDeliveryLoading = ref(false)
const linkedDeliveries = ref([])
const linkedOrderId = ref(null)            // 当前查询的订单 ID

// v1.1.41: 发货详情弹窗状态
const deliverySummaryDialogVisible = ref(false)
const deliverySummaryOrder = ref(null)
const deliverySummaryLoading = ref(false)
const deliverySummaryData = ref([])
const deliverySummaryOrderId = ref(null)

// v1.1.75 AI 解读弹窗
const aiDialogVisible = ref(false)
const aiLoading = ref(false)
const aiContent = ref('')
const aiModel = ref('')
const aiBillNo = ref('')

async function loadData() {
  loading.value = true
  try { data.value = (await salOrderApi.page(query)).data } finally { loading.value = false }
}

async function loadOptions() {
  if (customers.value.length === 0) customers.value = (await customerApi.list()).data || []
  if (warehouses.value.length === 0) warehouses.value = (await warehouseApi.list()).data || []
  if (products.value.length === 0) products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data?.records || []
}

function onAdd() {
  loadTaxSeparation()
  form.value = { id: null, customerId: null, warehouseId: null, deliveryDate: null, payType: 'PREPAY', remark: '', details: [] }
  loadOptions()
  dialogVisible.value = true
}

async function onEdit(row) {
  loadTaxSeparation()
  const r = await salOrderApi.detail(row.id)
  form.value = { ...r.data, details: r.data.details || [] }
  loadOptions()
  dialogVisible.value = true
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除订单 ${row.billNo}?`, '提示', { type: 'warning' })
  try {
    await salOrderApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// v1.1.11+ 审核 (status-only, 下游出库单触发库存账)
async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核订单 ${row.billNo}?`, '审核确认', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' })
  } catch { return }
  try {
    await salOrderApi.check(row.id); ElMessage.success('审核成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '审核失败')
  }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核订单 ${row.billNo}? 单据状态将回到「草稿」.`, '反审核确认', { type: 'warning', confirmButtonText: '确认反审核', cancelButtonText: '取消' })
  } catch { return }
  try {
    await salOrderApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '反审核失败')
  }
}

// v1.1.66: 列表多选 (仅已审核行可选, 未审核行 :selectable 置灰)
function onSelectionChange(rows) {
  selectedOrders.value = rows
}

// v1.1.66: 合并生成出库单 — 勾选 ≥2 张同客户已审核订单 → 一张出库单挂多订单明细
async function onMergeGenerateDelivery() {
  const rows = selectedOrders.value
  if (rows.length < 2) { ElMessage.warning('请勾选至少 2 张已审核订单'); return }
  const cust = rows[0].customerId
  const other = rows.find(r => r.customerId !== cust)
  if (other) {
    ElMessage.warning(`只能合并同一客户的订单: ${other.billNo} 客户不同, 请取消勾选`); return
  }
  await openGenerateDialog(rows)
}

// v1.1.35: 已审核订单 → 加载订单明细 → 弹出生成出库单弹窗 (单订单快捷入口, 复用 openGenerateDialog)
async function onGenerateDelivery(row) {
  await openGenerateDialog([row])
}

// v1.1.66: 通用生成弹窗 — orders: 已审核订单列表 (行, 单个或多个)
//  单订单: sourceOrder 展示 + orderId/orderNo 写主表 (旧行为不变)
//  多订单: 同客户, 明细逐行拼接, 每行 orderNo/orderDetailId 指向各自订单, 主表 orderId 置 null (溯源靠明细行)
async function openGenerateDialog(orders) {
  if (!orders || !orders.length) return
  generating.value = true
  try {
    const multi = orders.length > 1
    sourceOrders.value = multi ? orders : []
    // 逐订单拉明细 + 发货汇总, 拼成一张出库单明细 (保留每行归属)
    const allDetails = []
    let base = null
    for (let i = 0; i < orders.length; i++) {
      const row = orders[i]
      const r = await salOrderApi.detail(row.id)
      const o = r.data
      if (!o || !o.details || !o.details.length) {
        ElMessage.warning(`订单 ${o ? o.billNo : row.billNo} 无明细, 无法生成`); return
      }
      if (!base) base = o
      let shippedByDetail = new Map()
      try {
        const sr = await salOrderApi.getDeliverySummary(o.id)
        ;(sr.data || []).forEach(s => { if (s.orderDetailId != null) shippedByDetail.set(s.orderDetailId, s) })
      } catch (e) {
        console.warn('[openGenerateDialog] 加载发货汇总失败, 数量默认订单全量:', e)
      }
      for (const d of (o.details || [])) {
        const ship = shippedByDetail.get(d.id)
        const full = d.qty != null ? Number(d.qty) : 0
        const shipped = ship ? (ship.shippedQty != null ? Number(ship.shippedQty) : 0) : 0
        const remain = Math.max(0, +(full - shipped).toFixed(4))
        allDetails.push({
          productId: d.productId,
          productCode: d.productCode,
          productName: d.productName,
          spec: d.spec,
          unitId: d.unitId,
          unitName: d.unitName,
          qty: remain,
          _orderQty: full,          // 辅助: 该订单行全量 (供展示, 提交前剔除)
          _shippedQty: shipped,     // 辅助: 该订单行已发数量
          price: d.price != null ? Number(d.price) : 0,
          taxRate: d.taxRate != null ? Number(d.taxRate) : 13,
          lineNo: 0,                // 统一在下面重排
          orderDetailId: d.id,      // 关键: 每行指向各自订单明细, 审核时按行累计 (SalDeliveryService.check step 5)
          orderNo: o.billNo,        // v1.1.66: 来源订单号 (展示 + 追溯; 后端明细表无此列, 提交前剔除, 靠 orderDetailId 溯源)
          poNo: d.poNo || '',
          batchNo: '',
          locationName: '',
          remark: ''
        })
      }
    }
    allDetails.forEach((d, idx) => { d.lineNo = idx + 1 })

    // 重置 deliveryForm (公共字段取第一张订单 — 同客户)
    deliveryForm.billDate = new Date().toISOString().substring(0, 10)
    deliveryForm.customerId = base.customerId
    deliveryForm.customerName = base.customerName
    deliveryForm.address = ''
    deliveryForm.phone = ''
    deliveryForm.discountAmount = 0
    deliveryForm.tailAmount = 0
    deliveryForm.remark = ''
    deliveryForm.deliveryMethod = (base.deliveryMethodLabel ? mapCodeFromLabel(base.deliveryMethodLabel) : base.deliveryMethod) || ''
    deliveryForm.poNo = multi ? '' : (base.poNo || '')   // 多订单 PO 号各异, 头字段留空 (每行明细仍带各自 poNo)
    deliveryForm.orderId = multi ? null : base.id         // 多订单主表不写单一 orderId, 溯源走明细行
    // v1.1.66: 多订单 orderNo = 各订单号拼接 (仅作追溯文案; 每行明细仍带各自 poNo)
    deliveryForm.orderNo = multi ? orders.map(o => o.billNo).join(', ') : base.billNo
    deliveryForm.details = allDetails

    // 单订单快捷入口仍展示 sourceOrder (标题/提示兼容)
    sourceOrder.value = multi ? null : base

    await loadOptions()
    deliveryForm.warehouseId = base.warehouseId
    generateDialogVisible.value = true
  } catch (e) {
    ElMessage.error('加载订单失败: ' + (e.message || '未知错误'))
  } finally { generating.value = false }
}

// v1.1.35: 确认生成 → 走现有 salDeliveryApi.add (BaseMapper.insert 自动写 orderId/orderNo/orderDetailId)
async function onConfirmGenerate() {
  if (!deliveryForm.warehouseId) {
    ElMessage.warning('请选择仓库'); return
  }
  if (!deliveryForm.details.length) {
    ElMessage.warning('订单无明细'); return
  }
  submitting.value = true
  try {
    let totalAmount = 0
    deliveryForm.details.forEach(d => {
      totalAmount += (+d.qty || 0) * (+d.price || 0)
    })
    const discount = +deliveryForm.discountAmount || 0
    const tail = +deliveryForm.tailAmount || 0
    const payload = {
      ...deliveryForm,
      totalAmount: totalAmount - discount - tail,
      taxAmount: 0,
      totalAmountTax: totalAmount - discount - tail
    }
    // 剔除前端 UI 辅助字段, 避免脏数据传后端
    payload.details = deliveryForm.details.map(d => {
      const cleaned = { ...d }
      delete cleaned._units
      delete cleaned._priceFromUnit
      delete cleaned._orderQty     // v1.1.65: UI 辅助 (订单全量/已发), 不传后端
      delete cleaned._shippedQty
      delete cleaned.orderNo       // v1.1.66: 来源订单号仅展示, 溯源走 orderDetailId, 不传后端
      return cleaned
    })
    await salDeliveryApi.add(payload)
    const srcLabel = sourceOrders.value.length > 1
      ? `${sourceOrders.value.length} 张订单合并`
      : `源订单 ${sourceOrder.value ? sourceOrder.value.billNo : (deliveryForm.orderNo || '')}`
    ElMessage.success(`已生成出库单草稿 (${srcLabel})。请手动审核: 审核后才会扣库存、生成应收、计算成本与毛利`)
    generateDialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error((e && e.msg) || (e && e.message) || '生成失败')
  } finally { submitting.value = false }
}

// v1.1.35: 删除出库明细行 (生成出库单弹窗内)
function removeDeliveryDetail(index) {
  deliveryForm.details.splice(index, 1)
}

// v1.1.38: 查看该订单已关联的出库单 (追溯)
async function onViewLinkedDeliveries(row) {
  linkedOrderId.value = row.id
  linkedSourceOrder.value = row
  linkedDeliveryDialogVisible.value = true
  await reloadLinkedDeliveries()
}

async function reloadLinkedDeliveries() {
  if (!linkedOrderId.value) return
  linkedDeliveryLoading.value = true
  try {
    const r = await salDeliveryApi.pageByOrderId(linkedOrderId.value, { pageNum: 1, pageSize: 50 })
    linkedDeliveries.value = r.data?.records || []
  } catch (e) {
    ElMessage.error('加载关联出库单失败: ' + (e.message || '未知错误'))
  } finally { linkedDeliveryLoading.value = false }
}

function jumpToDelivery(deliveryId) {
  // v1.1.50: 修复跳转 — 关闭弹窗后 router.push, 避免 dialog overlay 拦截点击
  linkedDeliveryDialogVisible.value = false
  nextTick(() => {
    router.push({ path: '/sales/delivery', query: { id: deliveryId } })
  })
}

// v1.1.41: 查看订单发货详情 (已发/未发数量)
async function onViewDeliverySummary(row) {
  deliverySummaryOrderId.value = row.id
  deliverySummaryOrder.value = row
  deliverySummaryDialogVisible.value = true
  await reloadDeliverySummary()
}

async function reloadDeliverySummary() {
  if (!deliverySummaryOrderId.value) return
  deliverySummaryLoading.value = true
  try {
    const r = await salOrderApi.getDeliverySummary(deliverySummaryOrderId.value)
    deliverySummaryData.value = r.data || []
  } catch (e) {
    ElMessage.error('加载发货详情失败: ' + (e.message || '未知错误'))
  } finally { deliverySummaryLoading.value = false }
}

function addDetail() {
  if (taxSeparation.value === 'true') {
    form.value.details.push({ productId: null, productCode: '', productName: '', qty: null, price: 0, taxRate: 13 })
  } else {
    form.value.details.push({ productId: null, productCode: '', productName: '', qty: null, price: 0 })
  }
}

async function onProductChange(row) {
  const p = products.value.find(x => x.id === row.productId)
  if (!p) return
  row.productCode = p.productCode
  row.productName = p.productName
  // v1.1.38+: 自动带出 规格/型号/色号/单位 (打印预览用, 之前只带 productCode/productName, 规格列空白)
  if (p.spec != null)   row.spec = p.spec
  if (p.model != null)  row.model = p.model
  if (p.colorNo != null) row.colorNo = p.colorNo
  // 单位: 优先从商品主单位 id 带出, name 由后端 service 在 detail() 时注入
  if (!row.unitId && p.mainUnitId != null) row.unitId = p.mainUnitId
  // 优先取该客户对此商品的上次出库单价
  if (form.value.customerId && row.productId) {
    try {
      const res = await salOrderApi.getLastPrice(form.value.customerId, row.productId)
      if (res.data > 0) { row.price = res.data; return }
    } catch (e) { /* ignore */ }
  }
  row.price = p.salePrice || 0
}

async function onSubmit() {
  if (!form.value.customerId) { ElMessage.warning('请选择客户'); return }
  if (!form.value.details.length) { ElMessage.warning('请添加商品明细'); return }
  submitting.value = true
  try {
    // v1.1.19+: 含税单价. totalAmount = totalAmountTax = 开单金额, 不再算税.
    let totalQty = 0, totalAmount = 0
    form.value.details.forEach(d => {
      totalQty += d.qty || 0
      totalAmount += (d.qty || 0) * (d.price || 0)
    })
    form.value.totalQty = totalQty
    form.value.totalAmount = totalAmount
    form.value.taxAmount = 0
    form.value.totalAmountTax = totalAmount

    if (form.value.id) {
      await salOrderApi.update(form.value)
    } else {
      await salOrderApi.add(form.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

onMounted(loadData)

// ==================== v1.1.36: 打印 ====================
// 浏览器打印 (myprint-design)
const { doPrint } = usePrint()
// v1.1.38: 打印 header/detail map 对齐销售出库单 (SAL_DELIVERY)
const SAL_ORDER_HEADER_MAP = {
  billNo: 'billNo',
  billDate: 'billDate',
  customerName: 'customerName',
  warehouseName: 'warehouseName',
  address: 'address',
  phone: 'phone',
  // v1.1.38+: 交货方式/付款方式 取中文 label
  deliveryMethod: 'deliveryMethodLabel',
  payType: 'payTypeLabel',
  totalQty: 'totalQty',
  totalAmount: 'totalAmount',
  remark: 'remark'
}
const SAL_ORDER_DETAIL_MAP = {
  lineNo: 'lineNo',
  productCode: 'productCode',
  productName: 'productName',
  model: 'pModel',
  colorNo: 'pColorNo',
  spec: 'spec',
  unitName: 'unitName',
  qty: 'qty',
  price: 'price',
  amount: 'amount',
  taxRate: 'taxRate',
  batchNo: 'batchNo',
  poNo: 'poNo',
  locationName: 'locationName'
}
async function onPrint(row) {
  try {
    const r = await salOrderApi.detail(row.id)
    await doPrint({
      bizType: BIZ_TYPES.SAL_ORDER,
      bill: r.data || {},
      fieldMap: SAL_ORDER_HEADER_MAP,
      detailsKey: 'details',
      detailFieldMap: SAL_ORDER_DETAIL_MAP,
      customerId: r.data?.customerId || row.customerId || undefined
    })
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  }
}

// 飞鹅云打印
const feiePreviewVisible = ref(false)
const feiePreviewLoading = ref(false)
const feiePreviewHtml = ref('')
const feiePrinting = ref(false)
let feiePendingBillId = null
const feieCurrentBizType = ref('SAL_ORDER')

async function feiePreview(row) {
  feiePreviewLoading.value = true
  try {
    feiePendingBillId = row.id
    feiePreviewHtml.value = ''
    feiePreviewVisible.value = true
    const r = await feiePrintApi.preview('SAL_ORDER', row.id)
    feiePreviewHtml.value = r.data || ''
  } catch (e) {
    ElMessage.error('飞鹅预览失败: ' + (e.message || '未知错误'))
    feiePreviewVisible.value = false
  } finally { feiePreviewLoading.value = false }
}

async function feieDirectPrint(row) {
  feiePreview(row) // 先弹预览弹窗, 让用户可看一眼再确认打印
}

async function feieConfirmPrint() {
  if (!feiePendingBillId) return
  feiePrinting.value = true
  try {
    const res = await feiePrintApi.print('SAL_ORDER', feiePendingBillId)
    ElMessage.success(res.msg || '打印成功')
    feiePreviewVisible.value = false
  } catch (e) {
    ElMessage.error('飞鹅打印失败: ' + (e.message || '未知错误'))
  } finally { feiePrinting.value = false }
}

function onPrintCommand(cmd, row) {
  if (cmd === 'browser') return onPrint(row)
  if (cmd === 'feie-preview' || cmd === 'feie-print') {
    feieCurrentBizType.value = 'SAL_ORDER'
    return cmd === 'feie-preview' ? feiePreview(row) : feieDirectPrint(row)
  }
}

// v1.1.75 AI 解读: 调只读端点, 把模型分析结果渲染到弹窗 (markdown 用 pre 简显示, 避免引依赖)
async function onAiAnalyze(row) {
  aiBillNo.value = row.billNo || ''
  aiModel.value = ''
  aiContent.value = ''
  aiDialogVisible.value = true
  aiLoading.value = true
  try {
    const r = await aiApi.analyzeSalOrder(row.id)
    const d = (r && r.data) || {}
    aiModel.value = d.model || ''
    aiContent.value = d.content || '(模型未返回内容)'
  } catch (e) {
    aiContent.value = '解读失败: ' + ((e && (e.msg || e.message)) || '未知错误')
  } finally {
    aiLoading.value = false
  }
}
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }
/* v1.1.75 AI 解读弹窗 */
.ai-meta { padding: 8px 12px; background: #f5f7fa; border-radius: 4px; font-size: 13px;
  color: #606266; margin-bottom: 10px; display: flex; gap: 16px; }
.ai-meta b { color: #303133; }
.ai-body { min-height: 120px; max-height: 520px; overflow: auto; }
.ai-content { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 14px;
  line-height: 1.7; color: #303133; background: #fafafa; padding: 12px 14px; border-radius: 4px;
  font-family: -apple-system, "Segoe UI", "PingFang SC", sans-serif; }
</style>
