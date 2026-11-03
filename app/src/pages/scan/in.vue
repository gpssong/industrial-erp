<template>
  <view class="page">
    <!-- ===== 顶部: 面包屑 + 大标题 + 顶部按钮 ===== -->
    <view class="page-heading">
      <view>
        <view class="eyebrow">供应链 / 采购入库</view>
        <view class="h-title">采购入库</view>
      </view>
      <view class="heading-actions" v-if="!showPicker">
        <view class="ha-btn plain" @click="onSaveDraft">保存检验记录</view>
        <view class="ha-btn primary" :class="{ disabled: submitting }" @click="onSubmitAndInbound">
          {{ submitting ? '处理中…' : '✓ 生成验放单并入库' }}
        </view>
      </view>
    </view>

    <!-- ===== XK 合规受控条 ===== -->
    <view class="compliance-strip">
      <view class="cs-icon">✈</view>
      <view class="cs-body">
        <view class="cs-strong">XK 生产许可受控记录</view>
        <view class="cs-small">采购必须来源于审批计划；原料到货后先检验并上传供应商报告，检验合格方可生成入库单 · 标签照片随批归档 ≥ 2 年</view>
      </view>
      <view class="cs-badge">XK 受控</view>
    </view>

    <!-- 无订单时: 顶部采购订单选择条 (可点选, 选后即进入检验流程; 不阻断) -->
    <view v-if="showPicker" class="card picker">
      <view class="card-h">选择采购订单 (待到货检验)</view>
      <view class="muted small picker-tip">从采购订单中选择一张, 进入 XK 进货检验流程。没有可选订单时, 可在下方「到货基本信息」的「关联采购订单」里选择, 或先在 PC 端建采购订单。</view>
      <view v-for="o in orderList" :key="o.id" class="plan-row" @click="onPickOrder(o)">
        <view class="plan-main">
          <view class="plan-no">{{ o.billNo }}</view>
          <view class="muted small">{{ o.firstProductName || '' }}{{ o.firstProductName && o.supplierName ? ' · ' : '' }}{{ o.supplierName || '—' }}</view>
        </view>
        <view class="plan-qty muted small">{{ o.totalQty || 0 }}</view>
        <view class="plan-go">检验 ›</view>
      </view>
      <view v-if="!orderList.length && !loadingPlan" class="empty">暂无采购订单 — 可用下方表单直接开工</view>
      <view v-if="loadingPlan" class="empty">订单加载中…</view>
    </view>

    <!-- ===== 主流程 (始终渲染, 对齐 PC: 即使无订单也展示 3 步表单) ===== -->
    <view class="main-col">

    <!-- 三步进度条 (视觉, 对齐 PC) -->
    <view class="flow">
      <view class="flow-item" :class="{ active: flowStep === 0, done: flowStep > 0 }">
        <view class="flow-dot">📷</view>
        <view class="flow-txt"><b>到货验收</b><small>拍照记录到货原始标签</small></view>
      </view>
      <view class="flow-line" :class="{ done: flowStep > 0 }"></view>
      <view class="flow-item" :class="{ active: flowStep === 1, done: flowStep > 1 }">
        <view class="flow-dot">📄</view>
        <view class="flow-txt"><b>到货与原料检验</b><small>核对标签与检验数据</small></view>
      </view>
      <view class="flow-line" :class="{ done: flowStep > 1 }"></view>
      <view class="flow-item" :class="{ active: flowStep === 2, done: step3Done }">
        <view class="flow-dot">{{ step3Done ? '✓' : '③' }}</view>
        <view class="flow-txt"><b>自动入库</b><small>生成验放单</small></view>
      </view>
    </view>

    <!-- 采购单 / 到货检验记录卡 -->
    <view class="record-card">
      <view class="record-head">
        <view class="record-title"><b>采购单 / 到货检验记录</b></view>
        <view class="record-tag" :class="allPassed ? 'ok' : 'pending'">{{ allPassed ? '检验合格' : (items.length ? '检验中' : '待建检验单') }}</view>
      </view>
      <view class="record-line">
        <view class="record-name">{{ order.billNo || form.linkedOrderNo || '待选择采购订单' }}</view>
        <view class="record-sub">采购单号: {{ form.orderNo || '—' }}</view>
        <view class="record-sub">交货批次: {{ orderBatch || '—' }}</view>
      </view>
    </view>

    <!-- 到货基本信息 -->
    <view class="card">
      <view class="card-h">到货基本信息</view>
      <view class="info-grid">
        <view class="info-item">
          <text class="i-lbl">关联采购订单</text>
          <picker :range="orderOptions" :value="0" @change="onPickOrderByIndex">
            <view class="i-val pickable">{{ form.linkedOrderNo ? (form.linkedOrderNo + ' · ' + (order.supplierName || '')) : '选择采购订单…' }}</view>
          </picker>
        </view>
        <view class="info-item">
          <text class="i-lbl">供应商 / 到货来源</text>
          <view class="i-val static">{{ order.supplierName || '—' }}</view>
        </view>
        <view class="info-item">
          <text class="i-lbl">合同 / 采购单号</text>
          <input class="i-val input" v-model="form.orderNo" placeholder="CCL-A-2025-05-153" />
        </view>
        <!-- v1.1.163: 仓库改为现场可选 (以前只读订单仓库, 订单没填就永远显示 —) -->
        <view class="info-item">
          <text class="i-lbl">入库仓库</text>
          <picker :range="whNames" :value="warehouseIndex" @change="onPickWh">
            <view class="i-val pickable">{{ whName || (warehouses.length ? '选择仓库…' : '暂无可选仓库') }}</view>
          </picker>
        </view>
        <view class="info-item">
          <text class="i-lbl">验收日期</text>
          <picker mode="date" :value="form.arriveDate" @change="(e) => form.arriveDate = e.detail.value">
            <view class="i-val pickable">{{ form.arriveDate || '选择日期' }}</view>
          </picker>
        </view>
        <view class="info-item">
          <text class="i-lbl">到货数量</text>
          <view class="i-val static">{{ order.totalQty || 0 }}</view>
        </view>
        <view class="info-item">
          <text class="i-lbl">抽检数量</text>
          <input class="i-val input" type="digit" v-model="form.sampleQty" placeholder="5 袋 / 1 kg" />
        </view>
        <view class="info-item">
          <text class="i-lbl">检验报告号</text>
          <view class="i-val static">{{ createdReportNo || '保存后系统自动生成 (BG-YL-日期-流水)' }}</view>
        </view>
        <view class="info-item">
          <text class="i-lbl">检验人员</text>
          <input class="i-val input" v-model="form.inspector" placeholder="李敏" />
        </view>
      </view>
    </view>

      <!-- 到货原料与批号 (v1.1.163: 品名按采购订单明细分列, 每种原料各一行、各有自己的批号) -->
      <view class="card">
        <view class="card-h row">
          <text>到货原料与批号</text>
          <text class="muted small">{{ materials.length }} 种</text>
        </view>
        <view class="file-tip">多种原料同车到货时逐行分开录批号；先拍原料标签，再点本行「识别批号」按品名定向识别</view>
        <view v-for="(m, i) in materials" :key="m.orderDetailId || ('n' + i)" class="mat-item">
          <view class="mat-head">
            <text class="mat-name">{{ m.productName || '（订单未填品名）' }}</text>
            <text class="mat-meta muted small">{{ materialMeta(m) }}</text>
          </view>
          <view class="mat-field">
            <input class="i-val input batch-input" v-model="m.batchNo" placeholder="输入或识别原料批号" />
            <view class="ocr-btn" :class="{ 'ocr-disabled': !ocrEnabled || ocrLoadingIdx >= 0 || !photos.length }"
                  @click="runOcr(i, m)">{{ ocrLoadingIdx === i ? '识别中' : '🔍 识别批号' }}</view>
          </view>
        </view>
        <view v-if="!materials.length && !loadingItems" class="empty">采购订单没有原料明细 — 请先在 PC 端补明细行</view>
        <view v-if="loadingItems" class="empty">原料明细加载中…</view>
        <view class="photo-tip" v-if="materials.length && !photos.length">📷 识别批号需先上传至少 1 张原始标签照片；识别不到可手工录入</view>
      </view>

      <!-- 检验报告 · 采购合同 · 发票 (文件) -->
      <view class="card">
        <view class="card-h row">
          <text>检验报告、采购合同与发票</text>
          <view v-for="a in attachTypes" :key="a.type" class="mini-up" @click="onUploadAttach(a.type)">＋ 上传 {{ a.label }}</view>
        </view>
        <view class="file-tip">支持 PDF、JPG、PNG；三类文件均与采购批次强绑定，提交后不可单独删除</view>
        <view class="file-list">
          <view v-for="a in attachTypes" :key="a.type" class="file-row">
            <text class="file-ico">📄</text>
            <view class="file-meta">
              <view class="file-name">{{ a.label }}</view>
              <view class="file-sub">{{ hasAttach(a.type) ? '已上传 · 已核验' : '未上传' }}</view>
            </view>
            <view v-if="hasAttach(a.type)" class="file-view" @click="onPreviewAttach(a.type)">查看</view>
          </view>
        </view>
      </view>

      <!-- 检验项目判定表 -->
      <view class="card">
        <view class="card-h">检验项目判定</view>
        <view class="qa-table">
          <view class="qa-row qa-head">
            <text>检验项目</text><text>技术要求</text><text>检验方法</text><text>实际/结果</text><text>判定</text>
          </view>
          <view v-for="(it, i) in items" :key="i" class="qa-row">
            <text>{{ it.itemName }}</text>
            <text class="sub">{{ it.requirement || '—' }}</text>
            <text class="sub">{{ it.method || '—' }}</text>
            <input class="qa-result" v-model="it.resultValue" :placeholder="it.unit || '—'" />
            <view class="qa-tag" :class="it.passFlag === 1 ? 'ok' : 'bad'"
                  @click="onTogglePass(it, it.passFlag !== 1)">
              {{ it.passFlag === 1 ? '合格' : '不合格' }}
            </view>
          </view>
          <view v-if="!items.length && !loadingItems" class="empty">尚未建检验单 — 请先选择采购计划</view>
          <view v-if="loadingItems" class="empty">检验项加载中…</view>
        </view>
      </view>

      <!-- 原料标签 / 批次抽样照片 -->
      <view class="card">
        <view class="card-h row">
          <text>原料标签、批次抽样照片</text>
          <text class="muted small">{{ photos.length }} / 6</text>
        </view>
        <view class="file-tip">至少拍摄成品到货包装标签 1 张，建议 6 张以上</view>
        <view class="photo-row">
          <view v-for="(p, i) in photos" :key="i" class="photo-item" @click="onPreviewPhoto(p.fileUrl)">
            <image :src="p.fileUrl" mode="aspectFill" class="photo-img" />
            <view class="photo-x" @click.stop="onDeletePhoto(i)">×</view>
          </view>
          <view v-if="photos.length < 6" class="photo-add" @click="onUploadPhoto">
            <text class="plus">＋</text>
            <view class="add-txt">拍照 / 上传<br /><small>JPG / PNG</small></view>
          </view>
        </view>
        <view class="photo-tip" v-if="!photos.length">📷 至少 1 张原始到货标签照片，不可仅用供应商报告替代</view>
      </view>

      <!-- 固定底栏双按钮 (移动端主操作) -->
      <view class="footer-bar">
        <view class="btn btn-outline" @click="onSaveDraft">保存检验记录</view>
        <view class="btn btn-primary" :class="{ disabled: submitting || !orderId }" @click="onSubmitAndInbound">
          {{ submitting ? '处理中…' : '生成报告并入库' }}
        </view>
      </view>

      <!-- ===== 综合判定面板 ===== -->
      <view class="side-col">
        <view class="verdict-card" :class="verdictClass">
          <view class="v-ico" :class="{ ok: allPassed, fail: !allPassed && items.length > 0 }">{{ allPassed ? '✓' : (items.length ? '✗' : '·') }}</view>
          <view class="v-body">
            <view class="v-label">综合判定：
              <view class="v-tag" :class="allPassed ? 'ok' : 'bad'">{{ allPassed ? '合格' : (items.length ? '不合格' : '待定') }}</view>
            </view>
            <view class="v-sub">{{ items.length === 0 ? '选择采购计划后自动加载检验项'
              : allPassed ? '综合外观、密度、熔融指数等指标与批次报告，可放行动入库'
              : '存在不合格项，按 XK 要求本批原料不予放行动入库, 需退供应商或处置' }}</view>
          </view>
        </view>
        <view class="kv-card">
          <view class="kv-row"><small>检验日期</small><b>{{ form.arriveDate || '—' }}</b></view>
          <view class="kv-row"><small>判定人员</small><b>{{ form.inspector || '—' }}</b></view>
          <view class="kv-row"><small>判定结论</small>
            <b :class="allPassed ? 'v-ok' : 'v-bad'">{{ items.length === 0 ? '—'
              : allPassed ? (items.length + ' 项关键项合格')
              : ((items.length - items.filter((x) => x.passFlag === 1).length) + ' 项关键项不合格') }}</b>
          </view>
          <view class="kv-row" v-if="createdReceiptId"><small>入库单</small><b class="v-ok">已生成 DRAFT #{{ createdReceiptId }}</b></view>
        </view>
        <view class="v-note">综合外观、密度、熔融指数等指标，均可依据批次判定为合格。</view>
      </view>

      <!-- 占位: 给固定底栏 + 底部 tabBar 留出滚动空间 -->
      <view class="footer-spacer"></view>

    </view>
    <!-- main-col -->
  </view>
</template>

<script>
import api, { request } from '../../api/index.js'
import * as qmsApi from '../../api/qms.js'
import { ocrStatus, ocrLabelBatchNo } from '../../api/xk'
import { pickAndUploadImage } from '../../utils/upload.js'

export default {
  data() {
    return {
      orderId: null,
      order: {},
      iqc: {},
      iqcId: null,
      form: {
        orderNo: '',
        arriveDate: new Date().toISOString().slice(0, 10),
        sampleQty: '',
        inspector: '',
        linkedOrderNo: ''
      },
      // v1.1.163: 仓库现场可选 + 原料逐行批号 (一车多原料各归各批)
      warehouses: [],
      warehouseId: '',
      materials: [],
      ocrLoadingIdx: -1,
      items: [],
      attachments: [],
      photos: [],
      createdReceiptId: null,
      // v1.1.159: 报告号改由后端 createFromOrder 自动生成 (BG-YL-日期-流水), 入库成功后回填展示
      createdReportNo: '',
      submitting: false,
      // v1.1.159: 视觉 OCR 识别批号 (按钮启用取决于后端是否配了视觉模型 key)
      ocrEnabled: false,
      orderList: [],
      loadingPlan: false,
      loadingItems: false,
      attachTypes: [
        { type: 'REPORT',   label: '供应商检验报告' },
        { type: 'CONTRACT', label: '采购合同' },
        { type: 'INVOICE',  label: '采购发票' }
      ]
    }
  },
  computed: {
    showPicker() { return !this.orderId },
    step3Done() { return !!this.createdReceiptId },
    allPassed() {
      return this.items.length > 0 && this.items.every((it) => it.passFlag === 1)
    },
    // 到货基本信息里的「关联采购订单」picker 选项 (label 数组)
    // v1.1.131: 单号后带首个商品名称 (后端 selectPageWithProduct 注入 firstProductName),
    // 让选单时能直接看出是哪个原料, 不用逐个点开看明细
    orderOptions() {
      return this.orderList.length
        ? this.orderList.map((o) => {
            const no = o.billNo || ''
            const name = o.firstProductName || ''
            const head = name ? no + ' · ' + name : no
            return head + ' · ' + (o.supplierName || '') + ' · ' + (o.totalQty || 0)
          })
        : ['（暂无采购订单）']
    },
    // 交货批次: 取第一个已填的原料行批号 (检验单头只有一个批号列, 逐行批号落在入库单明细上)
    orderBatch() {
      const hit = this.materials.find((m) => (m.batchNo || '').trim())
      return hit ? String(hit.batchNo).trim() : ''
    },
    whNames() {
      // 空列表也要给 picker 一项, 否则点开是空白面板 (对齐 orderOptions 的兜底写法)
      return this.warehouses.length
        ? this.warehouses.map((w) => (w.warehouseName || '未命名仓库') + (w.warehouseType === 'RAW' ? '（原料仓）' : ''))
        : ['（暂无可选仓库）']
    },
    warehouseIndex() {
      const idx = this.warehouses.findIndex((w) => String(w.id) === String(this.warehouseId))
      return idx < 0 ? 0 : idx
    },
    whName() {
      const w = this.warehouses.find((x) => String(x.id) === String(this.warehouseId))
      if (w) return w.warehouseName || ''
      // 仓库名录还没到 / 没权限时, 至少把订单上的仓库名显示出来 (不显示裸 id)
      return this.order.warehouseName || ''
    },
    // 顶部三步视觉进度: 由填写程度驱动 (对齐 PC flowStep)
    flowStep() {
      if (this.step3Done) return 3
      if (this.photos.length > 0 && this.items.length > 0) return 2
      if (this.items.length > 0) return 1
      if (this.order.supplierName || this.orderId) return 1
      return 0
    },
    verdictClass() {
      if (this.createdReceiptId) return 'v-pass'
      if (this.iqc.result === 'PASS' || this.iqc.result === 'PASS_DISPOSITION') return 'v-pass'
      if (this.iqc.result === 'FAIL' || this.iqc.result === 'FAIL_REJECT') return 'v-fail'
      return 'v-pending'
    }
  },
  async onLoad(query) {
    this.orderId = query && query.orderId ? Number(query.orderId) : null
    this.loadOcr()
    // v1.1.163: 仓库名录与采购订单列表并行取; 深链带 orderId 时必须先等它们落地再 bootstrap
    //   (v1.1.142 同类坑: onLoad 里不 await 初始化 → 深链进来一片空兜底文案)
    await Promise.all([this.loadOrders(), this.loadWarehouses()])
    if (this.orderId) {
      await this.bootstrap()
    }
  },
  methods: {
    async loadOrders() {
      this.loadingPlan = true
      try {
        const r = await request({ url: '/purchase/order/page', method: 'GET', data: { pageNum: 1, pageSize: 100 } })
        this.orderList = (r && r.records) || []
      } catch (e) {
        console.warn('[scan/in] loadOrders failed:', e)
        this.orderList = []
      } finally {
        this.loadingPlan = false
      }
    },
    /**
     * v1.1.163: App 采购入库要选仓库.
     * 后端 /base/warehouse/list 已 OR 上 purchase:receipt:list (做采购的角色库里根本没有 base:warehouse:list,
     * 而它不在 PC「App 权限树」白名单里, 界面上授不出去) → App 端可直接读.
     * 万一还是 403/网络错, 回空数组: picker 显示「暂无可选仓库」, 提交时后端沿用订单仓库, 不阻断主流程.
     */
    async loadWarehouses() {
      try {
        const w = await api.warehouseList()
        const raw = (w && (w.data || w.records || w)) || []
        const list = Array.isArray(raw) ? raw : []
        this.warehouses = list.filter((x) => x && x.status !== 0)
      } catch (e) {
        console.warn('[scan/in] loadWarehouses failed:', e)
        this.warehouses = []
      }
      this.applyDefaultWarehouse()
    },
    // 默认仓库: 订单已指定 → 沿用; 否则按类型 RAW / 仓名含「原料」挑。
    // 绝不退到 warehouses[0] (index 0 往往是成品仓, 会静默把原料入到成品仓)。
    applyDefaultWarehouse() {
      if (this.warehouseId || !this.warehouses.length) return
      const orderWhId = this.order && this.order.warehouseId
      const hit = (orderWhId && this.warehouses.find((x) => String(x.id) === String(orderWhId)))
        || this.warehouses.find((x) => x.warehouseType === 'RAW')
        || this.warehouses.find((x) => (x.warehouseName || '').indexOf('原料') >= 0)
        || this.warehouses.find((x) => x.isDefault === 1)
      if (hit) this.warehouseId = String(hit.id)
    },
    onPickWh(e) {
      const w = this.warehouses[Number(e.detail.value)]
      if (w) this.warehouseId = String(w.id)
    },
    // v1.1.159: 探测视觉模型是否配置 (决定「识别批号」按钮能否用)
    async loadOcr() {
      try {
        const s = await ocrStatus()
        this.ocrEnabled = !!(s && s.data && s.data.enabled)
      } catch (e) {
        this.ocrEnabled = false
      }
    },
    /**
     * v1.1.163: 逐行识别 — 把本行品名交给后端拼进定向提示词,
     *   一车多种原料时不会把所有行都填成同一个批号。
     */
    async runOcr(i, m) {
      if (this.ocrLoadingIdx >= 0) return
      if (!this.ocrEnabled) {
        uni.showToast({ title: '未配置视觉模型, 请到 PC「系统设置→对接方式」填 OCR key', icon: 'none' })
        return
      }
      if (!this.photos.length) {
        uni.showToast({ title: '请先上传原料标签照片', icon: 'none' })
        return
      }
      this.ocrLoadingIdx = i
      try {
        const urls = this.photos.map((p) => p.fileUrl).filter(Boolean)
        const r = await ocrLabelBatchNo(urls, m && m.productName)
        const cand = (r && r.data && r.data.candidates) || []
        if (cand.length) {
          m.batchNo = cand[0]
          uni.showToast({ title: (m.productName || '本行') + '：识别到 ' + cand[0] + '，请核对标签', icon: 'none', duration: 3000 })
        } else {
          uni.showToast({ title: '未识别到该原料的批号，请手工录入', icon: 'none' })
        }
      } catch (e) {
        uni.showToast({ title: '识别失败：' + ((e && e.msg) || '未知错误'), icon: 'none' })
      } finally {
        this.ocrLoadingIdx = -1
      }
    },
    /** 明细行副标题: 规格 · 数量单位 */
    materialMeta(m) {
      const parts = []
      if (m.spec) parts.push(m.spec)
      if (m.qty !== null && m.qty !== undefined && m.qty !== '') parts.push(m.qty + (m.unitName || ''))
      return parts.join(' · ')
    },
    onPickOrder(o) {
      this.orderId = o.id
      // 换单 = 换仓库上下文: 清空现场选择, 让新订单的仓库/原料仓默认值重新生效
      this.warehouseId = ''
      this.materials = []
      this.bootstrap()
    },
    onPickOrderByIndex(e) {
      const idx = Number(e.detail.value)
      const o = this.orderList[idx]
      if (!o) {
        uni.showToast({ title: '请先在 PC 端创建采购订单', icon: 'none' })
        return
      }
      this.onPickOrder(o)
    },
    async bootstrap() {
      this.loadingItems = true
      try {
        const order = await request({ url: `/purchase/order/${this.orderId}`, method: 'GET' })
        this.order = order || {}
        this.applyDefaultWarehouse()
        const iqcId = await qmsApi.iqcCreateFromOrder(this.orderId)
        this.iqcId = iqcId
        const detail = await qmsApi.iqcDetailForOrder(iqcId)
        this.iqc = (detail && detail.head) || {}
        this.items = (detail && detail.items) || []
        this.attachments = (detail && detail.attachments) || []
        this.photos = this.attachments.filter((a) => ['LABEL_PHOTO', 'PACK_PHOTO', 'SAMPLE_PHOTO'].includes(a.attachType))
        // v1.1.163: 品名按采购订单明细逐行分列, 每行自己的批号 (订单上已录过的先带出; 无明细退检验单头品名)
        this.buildMaterials()
        // 回填: 检验人员 / 抽检数量 / 采购单号 / 供应商/仓库上下文
        // v1.1.159: 报告号不再回填 — 改由后端 createFromOrder 统一生成 BG-YL-日期-流水
        if (this.iqc.inspectorName) this.form.inspector = this.iqc.inspectorName
        if (this.order.totalQty) this.form.sampleQty = String(this.order.totalQty)
        if (this.order.billNo) {
          this.form.orderNo = this.order.billNo
          this.form.linkedOrderNo = this.order.billNo
        }
      } catch (e) {
        console.error('[scan/in] bootstrap failed:', e)
        if (e && e.code !== 401) {
          uni.showToast({ title: '加载失败: ' + (e.msg || e.message || '网络错'), icon: 'none', duration: 3000 })
        }
      } finally {
        this.loadingItems = false
      }
    },
    /**
     * v1.1.163: 把采购订单明细摊成"每种原料一行"的表单模型。
     * 明细为空时退一行"检验单头上的品名" (老单据/单行没明细也能录批号), 这种行没有 orderDetailId,
     * 批号走整单兜底而不是 lines。
     */
    buildMaterials() {
      const ds = (this.order && this.order.details) || []
      if (ds.length) {
        this.materials = ds.map((d) => ({
          orderDetailId: d.id ? String(d.id) : null,
          productName: d.productName || '',
          spec: d.spec || '',
          qty: d.qty === undefined || d.qty === null ? '' : d.qty,
          unitName: d.unitName || '',
          batchNo: d.batchNo || ''
        }))
        return
      }
      const name = this.iqc && this.iqc.productName
      this.materials = name
        ? [{ orderDetailId: null, productName: name, spec: '', qty: this.order.totalQty || '', unitName: '', batchNo: '' }]
        : []
    },
    hasAttach(type) {
      return this.attachments.some((a) => a.attachType === type)
    },
    async onUploadAttach(type) {
      try {
        const r = await pickAndUploadImage()
        await qmsApi.iqcBindAttachment(this.iqcId, {
          fileUrl: r.url,
          fileName: r.filename,
          fileSizeKb: Math.round((r.size || 0) / 1024),
          attachType: type
        })
        uni.showToast({ title: '已上传', icon: 'success' })
        const list = await qmsApi.iqcAttachments(this.iqcId)
        this.attachments = list || []
      } catch (e) {
        console.error('[upload attach] failed:', e)
        if (e && e.code !== 401) {
          uni.showToast({ title: '上传失败', icon: 'none' })
        }
      }
    },
    onPreviewAttach(type) {
      const a = this.attachments.find((x) => x.attachType === type)
      if (!a) return
      this.onPreviewPhoto(a.fileUrl)
    },
    async onUploadPhoto() {
      if (this.photos.length >= 6) {
        uni.showToast({ title: '最多 6 张', icon: 'none' })
        return
      }
      try {
        const r = await pickAndUploadImage()
        await qmsApi.iqcBindAttachment(this.iqcId, {
          fileUrl: r.url,
          fileName: r.filename,
          fileSizeKb: Math.round((r.size || 0) / 1024),
          attachType: 'LABEL_PHOTO'
        })
        const list = await qmsApi.iqcAttachments(this.iqcId)
        this.attachments = list || []
        this.photos = this.attachments.filter((a) => ['LABEL_PHOTO', 'PACK_PHOTO', 'SAMPLE_PHOTO'].includes(a.attachType))
        uni.showToast({ title: '已添加', icon: 'success' })
      } catch (e) {
        console.error('[upload photo] failed:', e)
        if (e && e.code !== 401) {
          uni.showToast({ title: '上传失败', icon: 'none' })
        }
      }
    },
    onDeletePhoto(i) {
      // 一期: 前端隐藏, 实际删除走 verify 流程; 简化避免误删
      this.photos.splice(i, 1)
    },
    onPreviewPhoto(url) {
      if (typeof uni === 'undefined' || !uni.previewImage) return
      uni.previewImage({ urls: [url], current: url })
    },
    onTogglePass(it, val) {
      it.passFlag = val ? 1 : 0
    },
    async onSaveDraft() {
      if (!this.iqcId) {
        uni.showToast({ title: '请先选择采购订单', icon: 'none' })
        return
      }
      try {
        await qmsApi.iqcSaveItems(this.iqcId, this.items)
        if (this.iqcId) {
          await request({
            url: `/qms/inspection/${this.iqcId}`,
            method: 'PUT',
            data: {
              id: this.iqcId,
              batchNo: this.orderBatch,
              sampleQty: this.form.sampleQty || 0
            }
          })
        }
        uni.showToast({ title: '已保存', icon: 'success' })
      } catch (e) {
        console.error('[save draft] failed:', e)
        if (e && e.code !== 401) {
          uni.showToast({ title: '保存失败: ' + (e.msg || e.message || '网络错'), icon: 'none' })
        }
      }
    },
    async onSubmitAndInbound() {
      if (!this.orderId || !this.iqcId) {
        uni.showToast({ title: '请先选择采购订单', icon: 'none' })
        return
      }
      if (this.submitting) return
      // v1.1.163: 入库仓库是后端硬门禁 — 现场没选且订单也没带, 提前拦下并说清原因
      if (!this.warehouseId && !this.order.warehouseId) {
        uni.showToast({
          title: this.warehouses.length ? '请选择入库仓库' : '仓库名录为空或无权读取, 请到 PC「基础数据 · 仓库管理」确认',
          icon: 'none', duration: 3000
        })
        return
      }
      if (!this.allPassed) {
        uni.showModal({
          title: '存在不合格项',
          content: '仍有检验项未判定为合格 (PASS), 提交后将无法入库。是否继续?',
          confirmText: '继续提交',
          cancelText: '返回修改',
          success: (r) => { if (r.confirm) this.doSubmit() }
        })
        return
      }
      this.doSubmit()
    },
    async doSubmit() {
      this.submitting = true
      try {
        await qmsApi.iqcSaveItems(this.iqcId, this.items)
        const photoUrls = JSON.stringify(this.photos.map((p) => p.fileUrl))
        // v1.1.159: 报告号不再前端手填 — 由后端 createFromOrder 统一生成 BG-YL-日期-流水
        await qmsApi.iqcSignForOrder(this.orderId, this.iqcId, {
          reportNo: '',
          sampleQty: Number(this.form.sampleQty) || 0,
          inspectorName: this.form.inspector || this.iqc.inspectorName,
          photoUrls
        })
        // v1.1.163: 现场选的仓库 + 逐行批号一起提交; 行上没填的, 后端回落整单 orderBatch
        const r = await qmsApi.purchaseCreateFromOrder(this.orderId, this.iqcId, this.orderBatch, {
          warehouseId: this.warehouseId,
          lines: this.materials.map((m) => ({ orderDetailId: m.orderDetailId, batchNo: m.batchNo }))
        })
        this.createdReceiptId = r.receiptId
        // 回填系统生成的检验报告号 (只读展示)
        try {
          const receipt = await request({ url: `/purchase/receipt/${this.createdReceiptId}`, method: 'GET' })
          this.createdReportNo = (receipt && receipt.inspectionReportNo) || ''
        } catch (e) {
          console.warn('[scan/in] 报告号回填失败:', e)
        }
        const detail = await qmsApi.iqcDetailForOrder(this.iqcId)
        this.iqc = (detail && detail.head) || {}
        uni.showToast({ title: '入库单已生成', icon: 'success', duration: 2000 })
      } catch (e) {
        console.error('[submit+inbound] failed:', e)
        if (e && e.code !== 401) {
          uni.showToast({ title: e.msg || e.message || '提交失败', icon: 'none', duration: 3000 })
        }
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style>
.page { padding: 20rpx 20rpx 0; background: #f5f6f8; min-height: 100vh; }

/* ===== 顶部 ===== */
.page-heading { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 20rpx; }
.eyebrow { font-size: 22rpx; color: #8a95a5; }
.h-title { font-size: 40rpx; font-weight: 700; color: #1a2b3c; margin-top: 4rpx; }
.heading-actions { display: flex; gap: 12rpx; }
.ha-btn { font-size: 24rpx; padding: 12rpx 20rpx; border-radius: 8rpx; }
.ha-btn.plain { background: #fff; color: #1677ff; border: 2rpx solid #1677ff; }
.ha-btn.primary { background: #1677ff; color: #fff; }
.ha-btn.disabled { opacity: 0.6; }

/* ===== XK 合规条 ===== */
.compliance-strip { display: flex; align-items: center; gap: 16rpx; background: linear-gradient(90deg, #eef5ff, #f7fbff);
  border: 2rpx solid #dfeaff; border-radius: 14rpx; padding: 18rpx 20rpx; margin-bottom: 20rpx; }
.cs-icon { width: 52rpx; height: 52rpx; border-radius: 10rpx; background: #1677ff; color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 28rpx; flex: 0 0 auto; }
.cs-body { flex: 1; min-width: 0; }
.cs-strong { font-size: 26rpx; font-weight: 700; color: #1a2b3c; }
.cs-small { font-size: 22rpx; color: #6b7a8d; margin-top: 4rpx; line-height: 1.5; }
.cs-badge { font-size: 22rpx; font-weight: 700; color: #1677ff; border: 2rpx solid #1677ff;
  border-radius: 20rpx; padding: 6rpx 16rpx; flex: 0 0 auto; }

/* ===== 三步流程 ===== */
.flow { display: flex; align-items: center; background: #fff; border: 2rpx solid #eef2f7; border-radius: 14rpx;
  padding: 20rpx; margin-bottom: 20rpx; }
.flow-item { display: flex; align-items: center; gap: 12rpx; }
.flow-dot { width: 56rpx; height: 56rpx; border-radius: 50%; background: #eef5ff; color: #6b7a8d;
  display: flex; align-items: center; justify-content: center; font-size: 28rpx; flex: 0 0 auto; transition: .2s; }
.flow-item.active .flow-dot { background: #1677ff; color: #fff; box-shadow: 0 4rpx 12rpx rgba(64,120,255,.35); }
.flow-item.done .flow-dot { background: #52c41a; color: #fff; }
.flow-txt { display: flex; flex-direction: column; }
.flow-txt b { font-size: 26rpx; color: #1a2b3c; }
.flow-txt small { font-size: 20rpx; color: #8a95a5; margin-top: 2rpx; }
.flow-line { flex: 1; height: 3rpx; background: #e6ecf4; margin: 0 14rpx; }
.flow-line.done { background: #52c41a; }

/* ===== 列布局 (移动端纵向: 主列 + 判定列) ===== */
.main-col { display: flex; flex-direction: column; gap: 20rpx; }
.side-col { margin-top: 20rpx; }

/* ===== 记录卡 ===== */
.record-card { background: #fff; border: 2rpx solid #eef2f7; border-radius: 14rpx; padding: 20rpx 22rpx; }
.record-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10rpx; }
.record-title b { font-size: 26rpx; color: #1a2b3c; }
.record-tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.record-tag.ok { background: #e6f7ec; color: #1f8a4c; }
.record-tag.pending { background: #fff4e6; color: #d96a2b; }
.record-line { display: flex; flex-wrap: wrap; gap: 8rpx 28rpx; align-items: center; }
.record-name { font-size: 34rpx; font-weight: 700; color: #1677ff; }
.record-sub { font-size: 22rpx; color: #8a95a5; }

/* ===== 通用卡 ===== */
.card { background: #fff; border: 2rpx solid #eef2f7; border-radius: 14rpx; padding: 20rpx 22rpx; }
.card-h { font-size: 28rpx; font-weight: 600; color: #1a2b3c; margin-bottom: 16rpx; }
.card-h.row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8rpx; }
.mini-up { font-size: 22rpx; color: #1677ff; background: #f0f6ff; border: 2rpx solid #dfeaff;
  padding: 6rpx 16rpx; border-radius: 8rpx; margin-right: 10rpx; }

/* ===== 到货基本信息 grid ===== */
.info-grid { display: flex; flex-direction: column; gap: 14rpx; }
.info-item { display: flex; align-items: center; gap: 12rpx; }
.i-lbl { width: 200rpx; font-size: 24rpx; color: #6b7a8d; flex: 0 0 auto; }
.i-val { flex: 1; font-size: 26rpx; min-height: 40rpx; padding: 10rpx 14rpx; border-radius: 8rpx; }
.i-val.static { background: #f7f9fc; color: #333; border: 2rpx solid #eef2f7; }
.i-val.input { background: #f5f6f8; }
.i-val.pickable { background: #f5f6f8; color: #1677ff; }

/* ===== 原料逐行批号 + OCR 识别按钮 (v1.1.159 / v1.1.163 分列) ===== */
.mat-item { padding: 16rpx 0; border-top: 2rpx solid #f2f5f9; }
.mat-item:first-of-type { border-top: none; }
.mat-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12rpx; margin-bottom: 10rpx; }
.mat-name { flex: 1; min-width: 0; font-size: 26rpx; font-weight: 600; color: #1a2b3c; }
.mat-meta { flex: 0 0 auto; text-align: right; }
.mat-field { display: flex; align-items: center; gap: 12rpx; }
.batch-input { flex: 1; min-width: 0; }
.ocr-btn { flex: 0 0 auto; font-size: 24rpx; color: #fff; background: #27ae60;
  padding: 10rpx 18rpx; border-radius: 8rpx; }
.ocr-disabled { opacity: 0.45; }

/* ===== 文件区 ===== */
.file-tip { font-size: 22rpx; color: #8a95a5; margin-bottom: 14rpx; }
.file-list { display: flex; flex-direction: column; gap: 10rpx; }
.file-row { display: flex; align-items: center; gap: 14rpx; padding: 16rpx 18rpx; background: #f7f9fc;
  border: 2rpx solid #eef2f7; border-radius: 10rpx; }
.file-ico { font-size: 34rpx; }
.file-meta { flex: 1; min-width: 0; }
.file-name { font-size: 26rpx; font-weight: 600; color: #1a2b3c; }
.file-sub { font-size: 20rpx; color: #8a95a5; margin-top: 4rpx; }
.file-view { font-size: 24rpx; color: #1677ff; }

/* ===== 检验项目判定表 ===== */
.qa-table { border: 2rpx solid #eef2f7; border-radius: 12rpx; overflow: hidden; }
.qa-row { display: flex; align-items: center; gap: 10rpx; padding: 14rpx; font-size: 24rpx;
  border-top: 2rpx solid #f2f5f9; }
.qa-row:first-child { border-top: none; }
.qa-row:nth-child(even) { background: #fbfcfe; }
.qa-head { background: #f0f5ff; color: #6b7a8d; font-weight: 600; font-size: 22rpx; }
.qa-row > text:nth-child(1) { flex: 1.2; font-weight: 600; color: #1a2b3c; }
.qa-row > text:nth-child(2) { flex: 1.4; }
.qa-row > text:nth-child(3) { flex: 1.1; }
.qa-result { flex: 1; font-size: 24rpx; padding: 8rpx 12rpx; background: #f5f6f8; border-radius: 8rpx; }
.qa-tag { width: 96rpx; text-align: center; font-size: 24rpx; padding: 8rpx 0; border-radius: 8rpx; flex: 0 0 auto; }
.qa-tag.ok { background: #e6f7ec; color: #1f8a4c; font-weight: 600; }
.qa-tag.bad { background: #ffeded; color: #d63b3b; font-weight: 600; }
.sub { color: #6b7a8d; font-size: 22rpx; }

/* ===== 照片区 ===== */
.photo-row { display: flex; flex-wrap: wrap; gap: 14rpx; }
.photo-item { width: 168rpx; height: 168rpx; border-radius: 10rpx; overflow: hidden; position: relative;
  border: 2rpx solid #eef2f7; background: #f7f9fc; }
.photo-img { width: 100%; height: 100%; }
.photo-x { position: absolute; top: 4rpx; right: 4rpx; width: 40rpx; height: 40rpx; line-height: 40rpx;
  text-align: center; background: rgba(0,0,0,.55); color: #fff; border-radius: 50%; font-size: 24rpx; }
.photo-add { width: 168rpx; height: 168rpx; border-radius: 10rpx; border: 2rpx dashed #1677ff;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6rpx;
  color: #1677ff; background: #f0f7ff; }
.plus { font-size: 56rpx; line-height: 1; }
.add-txt { font-size: 20rpx; color: #8a95a5; text-align: center; line-height: 1.4; }
.add-txt small { font-size: 18rpx; color: #b0b8c4; }
.photo-tip { font-size: 22rpx; color: #8a95a5; margin-top: 12rpx; }

/* ===== 综合判定面板 ===== */
.verdict-card { background: #fff; border: 2rpx solid #eef2f7; border-radius: 14rpx; padding: 22rpx;
  display: flex; gap: 16rpx; align-items: flex-start; }
.verdict-card.v-pass { border-color: #b7eb8f; }
.verdict-card.v-fail { border-color: #ffa39e; }
.v-ico { width: 60rpx; height: 60rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 36rpx; font-weight: 700; flex: 0 0 auto; }
.v-ico.ok { background: #e6f7ec; color: #1f8a4c; }
.v-ico.fail { background: #ffeded; color: #d63b3b; }
.v-body { flex: 1; min-width: 0; }
.v-label { font-size: 26rpx; color: #1a2b3c; margin-bottom: 6rpx; display: flex; align-items: center; gap: 10rpx; }
.v-tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; font-weight: 600; }
.v-tag.ok { background: #e6f7ec; color: #1f8a4c; }
.v-tag.bad { background: #ffeded; color: #d63b3b; }
.v-sub { font-size: 22rpx; color: #6b7a8d; line-height: 1.6; }
.kv-card { background: #fff; border: 2rpx solid #eef2f7; border-radius: 14rpx; padding: 0 22rpx; margin-top: 20rpx; }
.kv-row { display: flex; justify-content: space-between; padding: 16rpx 0; border-bottom: 2rpx solid #f4f6f9; }
.kv-row:last-child { border-bottom: none; }
.kv-row small { color: #8a95a5; font-size: 22rpx; }
.kv-row b { font-size: 24rpx; color: #1a2b3c; }
.v-ok { color: #1f8a4c; }
.v-bad { color: #d63b3b; }
.v-note { font-size: 22rpx; color: #8a95a5; line-height: 1.6; padding: 0 6rpx; margin-top: 12rpx; }

/* ===== 通用 ===== */
.muted { color: #8a95a5; }
.muted.small { font-size: 22rpx; }
.empty { padding: 40rpx 0; text-align: center; color: #8a95a5; font-size: 26rpx; }
.picker-tip { margin: 8rpx 0 16rpx; line-height: 1.5; }
.plan-row { display: flex; align-items: center; padding: 20rpx 0; border-top: 2rpx solid #f0f0f0; }
.plan-row:first-of-type { border-top: none; }
.plan-main { flex: 1; }
.plan-no { font-size: 28rpx; color: #1677ff; font-weight: 600; }
.plan-qty { font-size: 24rpx; margin: 0 16rpx; }
.plan-go { font-size: 24rpx; color: #52c41a; font-weight: 600; }

/* ===== 固定底栏 (移动端主操作) ===== */
.footer-bar { position: fixed; left: 0; right: 0; bottom: var(--window-bottom, 0px); background: #fff; padding: 16rpx 24rpx;
  box-shadow: 0 -2rpx 12rpx rgba(0,0,0,0.08); display: flex; gap: 16rpx; z-index: 99; }
/* 底栏本体 116rpx (84 按钮 + 上下 16 内边距), 多留 24rpx 给阴影 */
.footer-spacer { height: 140rpx; }
.btn { flex: 1; height: 84rpx; line-height: 84rpx; border-radius: 42rpx; text-align: center; font-size: 28rpx; font-weight: 600; }
.btn-outline { background: #fff; color: #1677ff; border: 2rpx solid #1677ff; }
.btn-primary { background: #1677ff; color: #fff; }
.btn-primary.disabled { background: #91caff; }
</style>
