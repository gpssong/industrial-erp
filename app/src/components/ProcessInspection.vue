<template>
  <view class="process-inspection">
    <view class="xk-strip">
      <view class="strip-bread">XK 生产 / {{ stageLabel }}工序检验</view>
      <view class="strip-title">{{ typeLabel }} ({{ inspectionType }}) 现场检验</view>
    </view>

    <view v-if="!canRead" class="card notice error">无工序检验查看权限，请联系管理员授权。</view>
    <template v-else>
      <view class="card">
        <view class="section-title">关联生产工单</view>
        <picker :range="orderOptions" :disabled="selectionLocked || ordersLoading || !orderList.length" @change="onPickOrder">
          <view class="picker-box" :class="{ active: selectedOrderId }">
            {{ selectedOrder ? orderLabel(selectedOrder) : (ordersLoading ? '加载工单列表中…' : '请选择生产工单') }}
          </view>
        </picker>
        <view v-if="selectedOrder" class="muted hint">工单状态：{{ selectedOrder.billStatus || '未知' }}</view>
        <view v-if="ordersError" class="error hint">{{ ordersError }}</view>
        <view class="pager">
          <button class="small-btn" :disabled="selectionLocked || ordersLoading || orderPage <= 1" @click="loadOrders(orderPage - 1)">上一页</button>
          <text class="muted">第 {{ orderPage }} 页 · {{ orderList.length }} 单</text>
          <button class="small-btn" :disabled="selectionLocked || ordersLoading || !hasMoreOrders" @click="loadOrders(orderPage + 1)">下一页</button>
          <button v-if="ordersError" class="small-btn" :disabled="selectionLocked || ordersLoading" @click="loadOrders(orderPage)">重试</button>
        </view>
        <view v-if="!ordersLoading && !ordersError && !orderList.length" class="muted hint">暂无生产工单。深链传入的工单将独立加载，不受此列表分页限制。</view>
      </view>

      <view v-if="selectedOrderId" class="card">
        <view class="section-title">实际生产工序{{ stage ? ' · 仅' + stageLabel : '' }}</view>
        <picker :range="processOptions" :disabled="selectionLocked || loading || !filteredProcesses.length" @change="onPickProcess">
          <view class="picker-box" :class="{ active: selectedProcessId }">
            {{ selectedProcess ? processLabel(selectedProcess) : (loading ? '加载工序中…' : '请选择实际工序') }}
          </view>
        </picker>
        <view v-if="selectedProcess" class="muted hint">工序状态：{{ processStatus(selectedProcess.status) }}{{ selectedProcess.workshop ? ' · ' + selectedProcess.workshop : '' }}</view>
        <view v-if="!loading && !error && !filteredProcesses.length" class="notice">
          该工单尚未配置或展开{{ stageLabel }}工序，请联系生产管理人员检查工序配置；不会改用其他工序模板。
          <button class="small-btn retry" :disabled="selectionLocked" @click="retry">重新加载工序</button>
        </view>
        <view v-if="stageLabel === '制袋' && inspectionType === 'PAT'" class="notice">制袋巡检须先完成同一实际工序的首检并签合格。</view>
        <view v-if="inspectionType === 'FAI'" class="muted hint">已下达或生产中的工单可首件试制，工序未开工不影响首检。</view>
      </view>

      <view v-if="loading" class="card notice">正在加载{{ typeLabel }}及实际工序模板，请稍候…</view>
      <view v-else-if="error" class="card notice error">
        <view>{{ error }}</view>
        <button class="small-btn retry" :disabled="selectionLocked" @click="retry">重试加载</button>
      </view>
      <template v-else-if="inspectionId">
        <view class="card verdict-card">
          <view class="verdict-line"><text class="section-title">{{ head.billNo || inspectionId }}</text><text :class="['verdict', checked ? (head.result === 'PASS' ? 'pass' : 'fail') : 'pending']">{{ verdictLabel }}</text></view>
          <view v-if="checked" class="hint">已签核，记录只读，不可修改或重复保存。</view>
          <view v-else class="muted hint">请逐项实测并明确判定；草稿允许保留待检项。</view>
          <view v-if="head.checkBy || head.inspectorName" class="muted hint">签核 / 检验人：{{ head.checkBy || head.inspectorName }}</view>
          <view v-if="head.checkTime" class="muted hint">签核时间：{{ head.checkTime }}</view>
        </view>

        <view class="card">
          <view class="section-title">抽样记录</view>
          <view class="form-row"><text class="form-label">批次 / 卷号</text><input v-model="form.batchNo" class="form-input" :disabled="readOnly" placeholder="请输入实际批次 / 卷号" /></view>
          <view class="form-row"><text class="form-label">抽检样本数</text><input v-model="form.sampleQty" class="form-input" :disabled="readOnly" type="digit" placeholder="实际抽检数，不是整单产量" /></view>
          <view class="form-row"><text class="form-label">合格数</text><input v-model="form.qualifiedQty" class="form-input" :disabled="readOnly" type="digit" placeholder="请输入实际合格数" /></view>
          <view class="form-row"><text class="form-label">不良数</text><input v-model="form.unqualifiedQty" class="form-input" :disabled="readOnly" type="digit" placeholder="无不良请明确填写 0" /></view>
        </view>

        <view class="card">
          <view class="section-title">{{ selectedProcess.processName }} · {{ typeLabel }}检验项 ({{ items.length }})</view>
          <view class="notice">按本工序模板逐项记录测量值、核验报告号或不适用原因，不自动填实测或判合格。食品接触报告类项目是核验有效报告，不是现场进行迁移试验。</view>
          <view v-if="!items.length" class="error hint">未配置检验项模板，不能保存或签核。请联系质量管理人员配置后重试。</view>
          <button v-if="!items.length && !checked" class="small-btn retry" :disabled="selectionLocked" @click="retry">重试加载模板</button>
          <view v-for="(item, index) in items" :key="item.id || item.lineNo || index" class="qa-row">
            <view class="qa-name">{{ index + 1 }}. {{ item.itemName || '未命名检验项' }}</view>
            <view class="qa-info">要求：{{ item.requirement || '模板未填写' }}</view>
            <view class="qa-info">方法：{{ item.method || '模板未填写' }}</view>
            <view class="qa-info">判定依据：{{ item.judgmentBasis || '模板未填写' }}</view>
            <view class="qa-info">单位：{{ item.unit || '未设置' }}</view>
            <textarea v-model="item.resultValue" class="qa-input" :disabled="readOnly" :maxlength="-1" auto-height placeholder="请记录测量值 / 核验报告号 / 不适用原因" />
            <view class="judgments">
              <button v-for="option in judgmentOptions" :key="option.value" :class="['judgment', option.className, { selected: item.passFlag === option.value }]" :disabled="readOnly" @click="setJudgment(item, option.value)">{{ option.label }}</button>
            </view>
          </view>
          <view v-if="items.length" class="muted hint">待检 {{ pendingCount }} 项 · 合格 {{ passCount }} 项 · 不合格 {{ failCount }} 项</view>
        </view>

        <view class="card">
          <view class="section-title">照片 ({{ photos.length }}/6)</view>
          <view class="photo-grid">
            <view v-for="(url, index) in photos" :key="index" class="photo-cell">
              <image :src="photoUrl(url)" class="photo-img" mode="aspectFill" @click="previewPhoto(url)" />
              <button v-if="!readOnly" class="photo-del" @click="removePhoto(index)">删除</button>
            </view>
            <button v-if="!checked && canWrite && photos.length < 6" class="photo-add" :disabled="readOnly" @click="uploadPhoto">{{ uploading ? '上传中…' : '+ 拍照 / 相册' }}</button>
          </view>
          <view class="muted hint">照片与本次检验记录一起保存。</view>
        </view>
      </template>
      <view v-if="!canWrite" class="card notice">当前仅可查看；保存、签核及新一轮巡检需要 quality:inspection:check 权限。</view>
      <view v-if="submitError" class="card notice error">{{ submitError }}（输入已保留）</view>
      <view class="foot">
        <template v-if="checked">
          <button v-if="inspectionType === 'PAT'" class="btn btn-sign" :disabled="!canWrite || loading || selectionLocked || !!error" @click="startNextRound">新一轮巡检</button>
          <view v-else class="foot-hint">首检已签核 · 只读</view>
        </template>
        <template v-else>
          <button class="btn btn-save" :disabled="!canSave" @click="submit(null)">保存草稿</button>
          <button class="btn btn-fail" :disabled="!canSave" @click="submit('FAIL')">不合格留痕</button>
          <button class="btn btn-sign" :disabled="!canSave" @click="submit('PASS')">签合格</button>
        </template>
        <view v-if="submitting" class="foot-hint">正在处理，请勿重复提交</view>
      </view>
    </template>
  </view>
</template>

<script>
import api from '../api/index.js'
import * as qmsApi from '../api/qms.js'
import { getAppPermissions, isAdmin } from '../utils/permission.js'
import { pickAndUploadImage } from '../utils/upload.js'
import { getApiBaseOverride } from '../utils/apiBase.js'
import { isH5Runtime } from '../utils/runtime.js'

const emptyForm = () => ({ sampleQty: '', qualifiedQty: '', unqualifiedQty: '', batchNo: '' })
const stringId = value => value == null ? '' : String(value)
const normalizeIds = object => Object.fromEntries(Object.entries(object || {}).map(([key, value]) => [key, value != null && (key === 'id' || key.endsWith('Id')) ? String(value) : value]))
const stageOf = process => {
  const name = String((process && process.processName) || '')
  return name.includes('制袋') ? 'BAG' : name.includes('吹膜') ? 'BLOW' : ''
}

export default {
  props: {
    orderId: { type: String, default: '' },
    processId: { type: String, default: '' },
    stage: { type: String, default: '' },
    inspectionType: { type: String, required: true }
  },
  data() {
    return {
      orderList: [], orderPage: 1, hasMoreOrders: false, ordersLoading: false, ordersError: '', orderRequestSeq: 0,
      selectedOrderId: '', selectedOrder: null, processes: [], selectedProcessId: '', selectedProcess: null,
      inspectionId: '', head: {}, items: [], photos: [], form: emptyForm(),
      loading: false, submitting: false, uploading: false, error: '', submitError: '', requestSeq: 0,
      retryProcessId: '', checkedRecords: {}, initialized: false,
      judgmentOptions: [{ value: -1, label: '待检', className: 'pending' }, { value: 1, label: '合格', className: 'pass' }, { value: 0, label: '不合格', className: 'fail' }]
    }
  },
  computed: {
    canWrite() { return isAdmin() || getAppPermissions().includes('quality:inspection:check') },
    canRead() { return this.canWrite || getAppPermissions().includes('quality:inspection:list') },
    typeLabel() { return this.inspectionType === 'FAI' ? '首检' : '巡检' },
    stageLabel() { return ({ BAG: '制袋', BLOW: '吹膜' })[this.stage || stageOf(this.selectedProcess)] || '制袋 / 吹膜' },
    filteredProcesses() { return this.processes.filter(process => stageOf(process) && (!this.stage || stageOf(process) === this.stage)) },
    orderOptions() { return this.orderList.map(this.orderLabel) },
    processOptions() { return this.filteredProcesses.map(this.processLabel) },
    selectionLocked() { return this.submitting || this.uploading },
    checked() { return this.head.status === 'CHECKED' },
    readOnly() { return !this.canWrite || this.loading || this.selectionLocked || this.head.status !== 'DRAFT' || !this.inspectionId || !!this.error },
    canSave() { return !this.readOnly && this.items.length > 0 },
    pendingCount() { return this.items.filter(item => item.passFlag === -1).length },
    passCount() { return this.items.filter(item => item.passFlag === 1).length },
    failCount() { return this.items.filter(item => item.passFlag === 0).length },
    verdictLabel() { return this.checked ? (this.head.result === 'PASS' ? '已签合格 PASS' : '已签核 ' + (this.head.result || '')) : '草稿 · 待签核' },
    routeKey() { return [this.orderId, this.processId, this.stage, this.inspectionType].join('|') }
  },
  watch: {
    routeKey() { if (this.initialized && !this.selectionLocked) this.initialize() }
  },
  mounted() {
    this.initialized = true
    this.initialize()
  },
  beforeUnmount() {
    this.requestSeq += 1
    this.orderRequestSeq += 1
  },
  methods: {
    orderLabel(order) { return (order.billNo || '#' + order.id) + (order.productName || order.firstProductName ? ' · ' + (order.productName || order.firstProductName) : '') },
    processLabel(process) { return process.processName + ' · ' + (process.workshop || '未设置车间') + ' · #' + process.id },
    processStatus(status) { return ({ 0: '未开工', 1: '进行中', 2: '已完成', 3: '已回退' })[String(status)] || String(status == null ? '未知' : status) },
    message(error, fallback) { return (error && (error.msg || error.message)) || fallback },
    clearRecord() {
      this.inspectionId = ''; this.head = {}; this.items = []; this.photos = []; this.form = emptyForm(); this.submitError = ''
    },
    initialize() {
      this.requestSeq += 1
      this.clearRecord()
      this.selectedOrderId = ''; this.selectedOrder = null; this.processes = []; this.selectedProcessId = ''; this.selectedProcess = null; this.error = ''; this.loading = false
      if (!this.canRead) return
      if (!['FAI', 'PAT'].includes(this.inspectionType) || (this.stage && !['BAG', 'BLOW'].includes(this.stage))) {
        this.error = '不支持的检验类型或工序阶段，请从正确入口进入。'
        return
      }
      this.loadOrders(1)
      if (this.orderId) this.selectOrder(this.orderId, this.processId)
    },
    async loadOrders(page) {
      if (this.selectionLocked || !this.canRead) return
      const seq = ++this.orderRequestSeq
      this.ordersLoading = true; this.ordersError = ''
      try {
        const result = await api.prdOrderPage({ pageNum: page, pageSize: 50 })
        if (seq !== this.orderRequestSeq) return
        if (!result || !Array.isArray(result.records)) throw new Error('工单列表返回异常')
        this.orderList = result.records.map(normalizeIds)
        this.orderPage = page
        this.hasMoreOrders = result.total != null ? page * 50 < Number(result.total) : this.orderList.length === 50
      } catch (error) {
        if (seq === this.orderRequestSeq) this.ordersError = this.message(error, '加载工单列表失败')
      } finally {
        if (seq === this.orderRequestSeq) this.ordersLoading = false
      }
    },
    onPickOrder(event) {
      if (this.selectionLocked) return
      const order = this.orderList[Number(event.detail.value)]
      if (order && stringId(order.id) !== this.selectedOrderId) this.selectOrder(stringId(order.id))
    },
    async selectOrder(orderId, preferredProcessId = '') {
      if (this.selectionLocked) return
      const seq = ++this.requestSeq
      this.clearRecord()
      this.selectedOrderId = stringId(orderId); this.selectedOrder = null; this.processes = []; this.selectedProcessId = ''; this.selectedProcess = null
      this.retryProcessId = stringId(preferredProcessId); this.error = ''; this.loading = true
      try {
        // 深链不依赖列表前 50 条；切单时同步清空所有旧记录并用序号隔离响应。
        const [order, processes] = await Promise.all([api.prdOrderDetail(this.selectedOrderId), qmsApi.inspectionProcesses(this.selectedOrderId)])
        if (seq !== this.requestSeq) return
        if (!order || stringId(order.id) !== this.selectedOrderId || !Array.isArray(processes)) throw new Error('工单或工序数据不完整，请重试')
        this.selectedOrder = normalizeIds(order)
        this.processes = processes.map(normalizeIds).filter(process => process.id && (!process.prdOrderId || process.prdOrderId === this.selectedOrderId))
        const preferred = this.retryProcessId && this.filteredProcesses.find(process => process.id === this.retryProcessId)
        if (this.retryProcessId && !preferred) throw new Error('指定工序不属于该工单或阶段，可能尚未配置 / 展开。不会改用吹膜或其他工序模板。')
        const selected = preferred || (this.filteredProcesses.length === 1 ? this.filteredProcesses[0] : null)
        if (selected) await this.loadProcess(selected, seq)
      } catch (error) {
        if (seq === this.requestSeq) { this.clearRecord(); this.error = this.message(error, '加载工序失败') }
      } finally {
        if (seq === this.requestSeq) this.loading = false
      }
    },
    onPickProcess(event) {
      if (this.selectionLocked || this.loading) return
      const process = this.filteredProcesses[Number(event.detail.value)]
      if (process && process.id !== this.selectedProcessId) this.selectProcess(process)
    },
    async selectProcess(process, nextRound = false) {
      const seq = ++this.requestSeq
      this.clearRecord(); this.error = ''; this.loading = true
      try {
        await this.loadProcess(process, seq, nextRound)
      } catch (error) {
        if (seq === this.requestSeq) { this.clearRecord(); this.error = this.message(error, '加载工序检验失败') }
      } finally {
        if (seq === this.requestSeq) this.loading = false
      }
    },
    recordKey(process) { return [this.inspectionType, this.selectedOrderId, process.id].join(':') },
    async loadProcess(process, seq, nextRound = false) {
      this.selectedProcess = process; this.selectedProcessId = stringId(process.id); this.retryProcessId = this.selectedProcessId
      const key = this.recordKey(process)
      // 已签结果在当前页面内只读复用；返回页、签核成功、重新选择同工序均不自动 ensure 下一轮。
      let result = !nextRound && this.checkedRecords[key]
      if (!result) {
        result = this.canWrite
          ? await qmsApi.ensureInspectionForProcess({ orderId: this.selectedOrderId, processId: this.selectedProcessId, inspectionType: this.inspectionType })
          : await this.readLatestRecord(process, seq)
      }
      if (seq !== this.requestSeq) return
      this.applyRecord(result, process)
      if (this.checked) this.checkedRecords[key] = result
      else if (nextRound) delete this.checkedRecords[key]
    },
    async readLatestRecord(process, seq) {
      // 只读账号不调用会建草稿的 POST。旧分页接口没有工序筛选，逐页定位实际工序的最近记录。
      for (let page = 1; seq === this.requestSeq; page += 1) {
        const result = await api.qmsInspectionPage({ pageNum: page, pageSize: 100, inspectionType: this.inspectionType })
        if (seq !== this.requestSeq) return null
        if (!result || !Array.isArray(result.records)) throw new Error('检验列表返回异常')
        const head = result.records.find(record => record.inspectionType === this.inspectionType && record.sourceBillType === 'PRD_PROCESS' && stringId(record.sourceBillId) === stringId(process.id))
        if (head) {
          const items = await qmsApi.iqcItems(stringId(head.id))
          return { head, items, process, order: this.selectedOrder, stage: stageOf(process) }
        }
        if (!result.records.length || (result.total != null ? page * 100 >= Number(result.total) : result.records.length < 100)) break
      }
      throw new Error('该实际工序尚无检验记录；当前仅可查看，请有签核权限的检验员创建。')
    },
    applyRecord(result, process) {
      if (!result || !result.head || !result.head.id || !Array.isArray(result.items) || !result.process || stringId(result.process.id) !== stringId(process.id) || !result.order || stringId(result.order.id) !== this.selectedOrderId || result.stage !== stageOf(process) || (this.stage && result.stage !== this.stage)) {
        throw new Error('检验数据与所选工单 / 工序不一致，已阻止保存，请重新加载')
      }
      if (result.head.inspectionType && result.head.inspectionType !== this.inspectionType) throw new Error('检验类型不匹配，已阻止保存')
      if (result.head.sourceBillType && (result.head.sourceBillType !== 'PRD_PROCESS' || stringId(result.head.sourceBillId) !== stringId(process.id))) throw new Error('检验未关联所选实际工序，已阻止保存')
      if (!['DRAFT', 'CHECKED'].includes(result.head.status)) throw new Error('当前检验状态不支持现场记录')
      this.head = normalizeIds(result.head)
      this.inspectionId = stringId(result.head.id)
      this.selectedOrder = normalizeIds(result.order)
      this.selectedProcess = normalizeIds(result.process)
      this.items = result.items.map(item => {
        const resultValue = item.resultValue == null ? '' : String(item.resultValue)
        const explicitFlag = item.passFlag != null && item.passFlag !== '' && [0, 1].includes(Number(item.passFlag))
        const pending = !explicitFlag || (result.head.status === 'DRAFT' && Number(item.passFlag) === 1 && !resultValue.trim())
        return { ...normalizeIds(item), passFlag: pending ? -1 : Number(item.passFlag), resultValue }
      })
      this.form = {
        batchNo: result.head.batchNo || '',
        sampleQty: result.head.sampleQty == null || (result.head.status === 'DRAFT' && Number(result.head.sampleQty) === 0) ? '' : String(result.head.sampleQty),
        qualifiedQty: result.head.qualifiedQty == null ? '' : String(result.head.qualifiedQty),
        unqualifiedQty: result.head.unqualifiedQty == null ? '' : String(result.head.unqualifiedQty)
      }
      this.photos = this.parsePhotos(result.head.photoUrls)
    },
    parsePhotos(raw) {
      if (!raw) return []
      let list = raw
      if (typeof raw === 'string') {
        try { list = JSON.parse(raw) } catch (error) { list = raw.split(',') }
      }
      if (!Array.isArray(list)) list = typeof list === 'string' ? list.split(',') : []
      return list.map(value => typeof value === 'string' ? value.trim() : '').filter(Boolean)
    },
    retry() {
      if (this.selectionLocked || this.loading) return
      if (!this.selectedOrderId) { this.initialize(); return }
      if (this.selectedProcess) this.selectProcess(this.selectedProcess)
      else this.selectOrder(this.selectedOrderId, this.retryProcessId)
    },
    setJudgment(item, value) { if (!this.readOnly) item.passFlag = value },
    photoUrl(url) {
      if (/^(https?:|data:|blob:)/i.test(url)) return url
      const base = getApiBaseOverride() || (isH5Runtime() ? window.location.origin + '/api' : 'http://home.93gushi.com:8088/api')
      return base.replace(/\/api\/?$/, '') + '/' + String(url).replace(/^\/+/, '')
    },
    previewPhoto(url) { uni.previewImage({ urls: this.photos.map(this.photoUrl), current: this.photoUrl(url) }) },
    removePhoto(index) { if (!this.readOnly) this.photos.splice(index, 1) },
    async uploadPhoto() {
      if (this.readOnly || this.photos.length >= 6) return
      const seq = this.requestSeq
      this.uploading = true; this.submitError = ''
      try {
        const result = await pickAndUploadImage()
        if (seq !== this.requestSeq) return
        if (!result || !result.url) throw new Error('未返回照片地址')
        this.photos.push(result.url)
      } catch (error) {
        if (seq === this.requestSeq && !/cancel/i.test((error && error.errMsg) || '')) this.submitError = this.message(error, '照片上传失败，请重试')
      } finally { if (seq === this.requestSeq) this.uploading = false }
    },
    quantity(value) {
      const text = String(value == null ? '' : value).trim()
      if (!text) return null
      return /^\d+(\.\d+)?$/.test(text) && Number.isFinite(Number(text)) ? Number(text) : NaN
    },
    validation(result) {
      if (!this.items.length) return '未配置检验项模板，不能保存或签核'
      const sample = this.quantity(this.form.sampleQty), qualified = this.quantity(this.form.qualifiedQty), unqualified = this.quantity(this.form.unqualifiedQty)
      if ([sample, qualified, unqualified].some(value => Number.isNaN(value))) return '抽检、合格及不良数只能填写非负数字'
      if (!result) return ''
      if (this.items.some(item => ![0, 1].includes(item.passFlag) || !String(item.resultValue || '').trim())) return '请完成全部检验项的记录及明确判定，不能留待检或空实测'
      if (result === 'PASS') {
        if (sample == null || sample <= 0 || qualified == null || unqualified == null) return '签合格须填写正数样本数及合格 / 不良数'
        if (Math.abs(qualified + unqualified - sample) > Number.EPSILON * Math.max(1, sample, qualified + unqualified) * 4) return '合格数与不良数之和必须等于抽检样本数'
        if (unqualified !== 0 || this.failCount > 0 || this.passCount !== this.items.length) return '存在不合格项或不良样本，不能签合格；可保存草稿或签不合格留痕'
      }
      if (result === 'FAIL' && this.failCount === 0 && !(unqualified > 0)) return '签不合格须至少有一项不合格，或不良数大于 0'
      return ''
    },
    confirm(title, content) {
      return new Promise(resolve => uni.showModal({ title, content, success: result => resolve(!!result.confirm), fail: () => resolve(false) }))
    },
    async submit(result) {
      if (!this.canSave) return
      const message = this.validation(result)
      if (message) { this.submitError = message; uni.showToast({ title: message, icon: 'none' }); return }
      const seq = this.requestSeq, id = this.inspectionId, process = this.selectedProcess
      this.submitting = true; this.submitError = ''
      try {
        if (result && !await this.confirm(result === 'PASS' ? '签合格' : '签不合格留痕', '签核后本次记录只读，不能修改。确认已完成实际检验并保存签核？')) return
        if (seq !== this.requestSeq) return
        const saved = await qmsApi.saveProcessInspectionRecord(id, {
          sampleQty: this.quantity(this.form.sampleQty), qualifiedQty: this.quantity(this.form.qualifiedQty), unqualifiedQty: this.quantity(this.form.unqualifiedQty),
          batchNo: this.form.batchNo.trim(), photoUrls: JSON.stringify(this.photos),
          items: this.items.map(item => ({ ...item, resultValue: item.resultValue.trim() })), result
        })
        if (seq !== this.requestSeq) return
        // 直接用返回的同一记录，不在签核成功后调用 ensure（PAT 会新建下一轮）。
        try {
          if (!saved || !saved.head || stringId(saved.head.id) !== id) throw new Error('保存返回的检验单不一致，请重新加载')
          this.applyRecord(saved, process)
        } catch (error) {
          this.error = this.message(error, '保存后数据异常，请重新加载')
          throw error
        }
        if (this.checked) this.checkedRecords[this.recordKey(process)] = saved
        uni.showToast({ title: result ? '检验已签核' : '草稿已保存', icon: 'success' })
      } catch (error) {
        if (seq === this.requestSeq) this.submitError = this.message(error, '保存失败，请重试')
      } finally { if (seq === this.requestSeq) this.submitting = false }
    },
    async startNextRound() {
      if (!this.canWrite || !this.checked || this.inspectionType !== 'PAT' || this.loading || this.selectionLocked) return
      this.submitting = true
      try {
        if (!await this.confirm('新一轮巡检', '保留本轮已签记录，并开始同一工序的新一轮巡检？已有草稿会复用。')) return
        await this.selectProcess(this.selectedProcess, true)
      } finally { this.submitting = false }
    }
  }
}
</script>

<style scoped>
.process-inspection { padding: 12px; padding-bottom: calc(128px + env(safe-area-inset-bottom)); background: #f5f7fa; min-height: 100vh; box-sizing: border-box; }
.xk-strip { background: #1e6091; color: #fff; border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.strip-bread { font-size: 11px; opacity: .8; }
.strip-title { font-size: 16px; font-weight: 600; margin-top: 3px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.muted { color: #747982; font-size: 12px; }
.hint { margin-top: 8px; font-size: 12px; line-height: 1.6; }
.notice { background: #f0f7ff; color: #45647a; padding: 10px; border-radius: 6px; font-size: 12px; line-height: 1.7; margin-top: 10px; }
.error { color: #b63838; overflow-wrap: anywhere; }
.picker-box { min-height: 40px; line-height: 1.6; display: flex; align-items: center; border: 1px solid #dcdfe6; border-radius: 6px; padding: 4px 10px; font-size: 14px; color: #303133; overflow-wrap: anywhere; }
.picker-box.active { border-color: #1e6091; background: #ecf5fb; }
.pager { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 10px; }
.small-btn { display: inline-block; margin: 0; padding: 0 10px; min-height: 32px; line-height: 32px; font-size: 12px; background: #ecf5fb; color: #1e6091; }
.retry { margin-top: 8px; }
.verdict-card { background: #f0f7ff; }
.verdict-line { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; flex-wrap: wrap; overflow-wrap: anywhere; }
.verdict { font-size: 12px; padding: 4px 8px; border-radius: 12px; }
.form-row { display: flex; align-items: center; margin-bottom: 10px; gap: 8px; }
.form-label { width: 76px; flex-shrink: 0; font-size: 13px; color: #606266; }
.form-input { flex: 1; min-width: 0; height: 38px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 13px; box-sizing: border-box; }
.qa-row { border-top: 1px solid #ebeef5; padding: 14px 0; }
.qa-name { font-size: 14px; font-weight: 600; color: #303133; line-height: 1.6; }
.qa-info { font-size: 12px; color: #606266; margin-top: 5px; line-height: 1.6; overflow-wrap: anywhere; white-space: pre-wrap; }
.qa-input { width: 100%; min-height: 60px; margin-top: 10px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 8px; font-size: 13px; box-sizing: border-box; }
.judgments { display: flex; gap: 8px; margin-top: 10px; }
.judgment { flex: 1; font-size: 13px; height: 36px; line-height: 34px; border: 1px solid #dcdfe6; border-radius: 6px; margin: 0; padding: 0 4px; background: #fff; color: #606266; }
.judgment.selected { border: 2px solid currentColor; line-height: 32px; font-weight: 600; }
.verdict.pass, .judgment.pass.selected { background: #e7f4e6; color: #347a36; }
.verdict.fail, .judgment.fail.selected { background: #fdeaea; color: #b63838; }
.verdict.pending, .judgment.pending.selected { background: #edf0f5; color: #596477; }
.photo-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.photo-cell { position: relative; }
.photo-img { width: 100%; height: 84px; border-radius: 6px; background: #f4f4f5; }
.photo-del { position: absolute; top: 2px; right: 2px; background: rgba(0,0,0,.6); color: #fff; font-size: 11px; padding: 0 6px; line-height: 26px; margin: 0; }
.photo-add { width: 100%; height: 84px; border: 1px dashed #c0c4cc; border-radius: 6px; display: flex; align-items: center; justify-content: center; font-size: 12px; color: #1e6091; background: #f8fbff; padding: 4px; }
.foot { position: fixed; z-index: 10; bottom: 0; left: 0; right: 0; background: #fff; padding: 10px 12px; padding-bottom: calc(10px + env(safe-area-inset-bottom)); box-shadow: 0 -2px 8px rgba(0,0,0,.06); display: flex; flex-wrap: wrap; gap: 8px; }
.btn { flex: 1; min-width: 0; height: 42px; line-height: 42px; border: none; border-radius: 6px; font-size: 13px; margin: 0; padding: 0 6px; }
.btn-save { background: #ecf5fb; color: #1e6091; }
.btn-fail { background: #fdeaea; color: #b63838; }
.btn-sign { background: #1e6091; color: #fff; }
.foot-hint { width: 100%; text-align: center; font-size: 12px; line-height: 24px; color: #606266; }
button::after { border: none; }
button[disabled] { opacity: .55; }
</style>
