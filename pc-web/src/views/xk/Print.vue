<template>
  <div class="xk-print">
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">经营支持 / 单据中心</div>
        <h1 class="xk-title">单据与打印</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onPdf"><el-icon><Download /></el-icon>导出 PDF</el-button>
        <el-button type="primary" @click="onPrint" :loading="printing"><el-icon><Printer /></el-icon>打印当前单据</el-button>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>PDF 生成后计算文件校验值，记录生成时间与操作人，导出文件只读存档</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <div class="print-workspace">
      <!-- 左: 单据选择与打印内容 -->
      <aside class="print-settings card">
        <div class="ps-title">选择业务单据</div>
        <div class="ps-intro">可跨采购、生产、质量检验和设备维保选择需要输出的单据</div>

        <div class="ps-field">
          <label>单据类型</label>
          <el-select v-model="docType" style="width:100%">
            <el-option-group v-for="g in docGroups" :key="g.label" :label="g.label">
              <el-option v-for="d in g.items" :key="d.value" :label="d.label" :value="d.value" />
            </el-option-group>
          </el-select>
        </div>

        <div class="ps-field">
          <label>业务单号</label>
          <el-select v-model="docType" style="width:100%">
            <el-option :label="doc.code" :value="docType" />
          </el-select>
        </div>

        <div class="ps-section-title">自由选择打印内容</div>
        <div class="print-options">
          <label v-for="opt in sectionOpts" :key="opt.key">
            <el-checkbox v-model="sections[opt.key]" />
            <span>{{ opt.label }}</span>
          </label>
        </div>

        <div class="print-two-fields">
          <div class="ps-field">
            <label>纸张</label>
            <el-select v-model="paper" style="width:100%">
              <el-option label="A4 纵向" value="A4P" />
              <el-option label="A4 横向" value="A4L" />
            </el-select>
          </div>
          <div class="ps-field">
            <label>份数</label>
            <el-input-number v-model="copies" :min="1" :max="99" style="width:100%" />
          </div>
        </div>

        <div class="ps-note">
          <strong>所见即所得</strong>
          <p>右侧预览随选择实时更新。照片自动等比缩放，PDF 与纸质打印版式保持一致。</p>
        </div>
      </aside>

      <!-- 右: 纸张预览 -->
      <div class="paper-wrap" :class="'size-' + paper">
        <article class="paper">
          <div class="paper-brand">
            <span class="brand-symbol"><i /><i /><i /></span>
            <div>
              <strong>{{ systemName }}</strong>
              <small>华塑包装 · 吹膜成膜</small>
            </div>
            <el-tag type="success" effect="light">已审核 · 只读</el-tag>
          </div>

          <div class="paper-title">{{ doc.title }}</div>

          <div v-if="sections.audit" class="paper-code">
            唯一编号：{{ doc.code }} · 生成时间：2025-06-18 14:26:32 · 操作人：陈军
          </div>

          <div v-if="sections.basic" class="paper-info">
            <div v-for="m in doc.meta" :key="m[0]">
              <small>{{ m[0] }}</small>
              <strong>{{ m[1] }}</strong>
            </div>
          </div>

          <div v-if="sections.details" class="paper-body">
            <table class="paper-table detail">
              <thead><tr><th v-for="h in doc.headers" :key="h">{{ h }}</th></tr></thead>
              <tbody><tr><td v-for="c in doc.row" :key="c">{{ c }}</td></tr></tbody>
            </table>

            <div v-if="sections.photos && doc.photos" class="paper-photo-block">
              <div class="paper-section-title">现场照片与业务附件</div>
              <div class="paper-photos">
                <figure>
                  <img src="https://images.unsplash.com/photo-1605000797499-95a51c5269ae?w=600" alt="附件1" />
                  <figcaption>现场作业照片</figcaption>
                </figure>
                <figure>
                  <img src="https://images.unsplash.com/photo-1581092160562-41e83e438379?w=600" alt="附件2" />
                  <figcaption>设备 / 批次附件</figcaption>
                </figure>
              </div>
            </div>
            <div v-if="sections.photos && !doc.photos" class="paper-no-photo">
              <el-icon><Document /></el-icon><span>该单据无强制照片附件</span>
            </div>

            <div v-if="sections.remark" class="paper-remark"><strong>备注 / 处理结果：</strong>{{ doc.remark }}</div>
          </div>

          <div v-if="sections.signatures" class="paper-sign">
            <span v-for="s in doc.signatures" :key="s">{{ s }}</span>
          </div>

          <div v-if="sections.audit" class="paper-footer">
            华塑智造 · {{ doc.group }} · 系统生成 · 校验值 SHA256: 4FE8…92A1 · 不可篡改
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import {
  Download, Printer, CircleCheckFilled, Document
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useSystemName } from '@/composables/useSystemName'

const { systemName } = useSystemName()
const printing = ref(false)
const paper = ref('A4P')
const copies = ref(1)

// 13 种单据 (对齐 Figma mockup documents)
const docGroups = [
  { label: '采购与原料', items: [
    { value: 'purchasePlan', label: '采购计划单' },
    { value: 'purchaseOrder', label: '采购订单' },
    { value: 'rawInspection', label: '原料进货检验记录' },
    { value: 'rawReport', label: '原料检验报告' } ] },
  { label: '设备维保', items: [
    { value: 'maintenancePlan', label: '维保计划单' },
    { value: 'maintenanceRecord', label: '维保记录单' } ] },
  { label: '生产管理', items: [
    { value: 'productionOrder', label: '生产工单' },
    { value: 'productionRecord', label: '生产记录单' } ] },
  { label: '质量与XK', items: [
    { value: 'filmInspection', label: 'PE膜半成品检验记录' },
    { value: 'filmReport', label: 'PE膜半成品检验报告' },
    { value: 'bagInspection', label: 'PE袋成品检验记录' },
    { value: 'bagReport', label: 'PE袋成品检验报告' },
    { value: 'environmentReport', label: '生产环境检查报告' } ] }
]

const documents = {
  purchasePlan: { title: '采购计划单', code: 'CGJH-20250618-006', group: '采购管理', meta: [['计划日期','2025-06-18'],['需求部门','原料仓库'],['计划人员','张伟'],['采购周期','7 天']], headers: ['物料名称','规格型号','计划数量','需求日期','用途'], row: ['LLDPE','7042','12,800 kg','2025-06-25','食品袋生产'], remark: '根据库存预警和未来 7 天生产计划自动生成。', photos: false, signatures: ['计划人：张伟','复核人：李敏','批准人：陈军'] },
  purchaseOrder: { title: '采购订单', code: 'CG-20250618-018', group: '采购管理', meta: [['供应商','中石化华东'],['订单日期','2025-06-18'],['交货仓库','原料一库'],['采购人员','张伟']], headers: ['物料名称','规格型号','数量','含税单价','金额'], row: ['LLDPE','7042','12,800 kg','¥ 6.75','¥ 86,400.00'], remark: '供应商须随货提供检验报告及批次质量证明。', photos: false, signatures: ['采购员：张伟','财务：刘芳','批准人：陈军'] },
  rawInspection: { title: '原料进货检验记录', code: 'YL-JY-20250618-031', group: '原料质量控制', meta: [['原料名称','LLDPE 7042'],['供应商','中石化华东'],['供应商批号','RM20250618-031'],['检验人员','李敏']], headers: ['检验项目','技术要求','检验方法','实测结果','判定'], row: ['熔体质量流动速率','2.0±0.5g/10min','熔融指数仪','2.18','合格'], remark: '包装标签、感官、物理指标、供应商检验报告、采购合同及发票查验合格。', photos: true, signatures: ['检验员：李敏','复核人：周洁','质量负责人：陈军'] },
  rawReport: { title: '原料检验报告', code: 'BG-YL-0618-31', group: '原料质量控制', meta: [['样品名称','LLDPE 7042'],['样品批号','RM20250618-031'],['到货数量','12,800 kg'],['报告日期','2025-06-18']], headers: ['检验项目','标准要求','检验结果','单项判定','总判定'], row: ['全项目','符合采购质量标准','符合','合格','准予入库'], remark: '该批原料经进货查验和抽样检验符合要求，供应商报告核验一致，准予入库。', photos: false, signatures: ['检验：李敏','审核：周洁','批准：陈军'] },
  maintenancePlan: { title: '设备维保计划单', code: 'JH-2025-018', group: '设备维保', meta: [['设备编号','FM-02'],['设备名称','2# 三层共挤吹膜机'],['计划日期','2025-06-18'],['负责人','王建国']], headers: ['维保项目','维保周期','计划工时','所需配件','状态'], row: ['更换滤网、清理模头','每 30 天','2 小时','滤网组件 × 2','执行中'], remark: '按设备累计运行时间和预防性维护周期生成。', photos: false, signatures: ['计划人：王建国','设备主管：陈杰','批准人：陈军'] },
  maintenanceRecord: { title: '设备维保记录单', code: 'WB-20250521-008', group: '设备维保', meta: [['设备编号','FM-02'],['完成时间','2025-05-21 14:36'],['维保人员','王建国'],['来源计划','JH-2025-012']], headers: ['维保项目','处理内容','更换部件','试机结果','状态'], row: ['更换牵引辊轴承','拆除旧轴承并清洁安装位','轴承 × 2','运行平稳','已完成'], remark: '试机 30 分钟，设备温度、张力及运行噪音正常。', photos: true, signatures: ['维保人：王建国','复核人：陈杰','质量 QA：李敏'] },
  productionOrder: { title: '生产工单', code: 'WO-250618-08', group: '生产管理', meta: [['产品类别','食品袋 · XK受控'],['生产设备','FM-01'],['计划日期','2025-06-18'],['当班班组','早班 A 组']], headers: ['产品名称','计划数量','原料批次','执行标准','状态'], row: ['PE 食品保鲜袋','5,200 kg','RM20250618-031','GB 4806.7','生产中'], remark: '生产前确认原料放行状态、设备清洁状态和人员卫生要求。', photos: false, signatures: ['计划员：周明','班组长：刘强','质量 QA：李敏'] },
  productionRecord: { title: '生产记录单', code: 'SCJL-20250618-008', group: '生产管理', meta: [['生产时间','08:12–15:46'],['设备编号','FM-01'],['当班人员','刘强、周明、徐磊'],['关联工单','WO-250618-08']], headers: ['产品名称','投入数量','产出数量','损耗','成品批号'], row: ['PE 食品保鲜袋','5,260 kg','5,200 kg','60 kg','CP20250618-0088'], remark: '过程参数稳定，首检、巡检及成品检验结果均合格。', photos: true, signatures: ['操作工：刘强','班组长：周明','质量 QA：李敏'] },
  filmInspection: { title: 'PE膜半成品检验记录', code: 'BPC-JY-20250618-016', group: '质量检验', meta: [['半成品批号','BPC20250618-016'],['关联工单','WO-250618-08'],['检验时间','2025-06-18 14:06'],['检验人员','李敏']], headers: ['检验项目','技术要求','检验方法','实测结果','判定'], row: ['平均厚度','0.030±0.003mm','测厚仪','0.030mm','合格'], remark: '外观、厚度、幅宽、电晕值及拉伸强度检验合格，可进入制袋工序。', photos: true, signatures: ['检验员：李敏','复核人：周洁','质量负责人：陈军'] },
  filmReport: { title: 'PE膜半成品检验报告', code: 'BG-BPC-0618-16', group: '质量检验', meta: [['样品名称','PE食品包装膜'],['样品批号','BPC20250618-016'],['检验依据','Q/HS 02'],['报告日期','2025-06-18']], headers: ['检验项目','标准要求','检验结果','单项判定','结论'], row: ['全项目','符合内控标准','符合','合格','允许转序'], remark: '该批半成品经检验符合企业内控标准要求，准予转入制袋工序。', photos: false, signatures: ['检验：李敏','审核：周洁','批准：陈军'] },
  bagInspection: { title: 'PE袋成品检验记录', code: 'CP-JY-20250618-008', group: '质量检验', meta: [['成品批号','CP20250618-0088'],['关联半成品','BPC20250618-016'],['抽样数量','32 个'],['检验人员','李敏']], headers: ['检验项目','技术要求','检验方法','实测结果','判定'], row: ['热合强度','≥15N/15mm','拉力机','18.2N/15mm','合格'], remark: '外观、尺寸、厚度、热合强度、密封性能及感官要求检验合格。', photos: true, signatures: ['检验员：李敏','复核人：周洁','质量负责人：陈军'] },
  bagReport: { title: 'PE袋成品检验报告', code: 'BG-CP-0618-08', group: '质量检验', meta: [['产品名称','PE食品保鲜袋'],['成品批号','CP20250618-0088'],['执行标准','GB 4806.7'],['报告日期','2025-06-18']], headers: ['检验项目','标准要求','检验结果','单项判定','总判定'], row: ['全项目','符合产品标准','符合','合格','合格'], remark: '该批产品经检验符合相应标准及 XK 质量控制要求，准予出厂。', photos: false, signatures: ['检验：李敏','审核：周洁','批准：陈军'] },
  environmentReport: { title: '生产环境检查报告', code: 'HJ-JC-BG-20250618-006', group: 'XK生产环境检查', meta: [['检查区域','吹膜洁净区'],['检查日期','2025-06-18'],['检查人员','李敏'],['整改负责人','赵海峰']], headers: ['检查项目','检查要求','检查结果','问题等级','整改状态'], row: ['现场无非生产用品及纸箱杂物','洁净区无杂物和污染风险','发现外包装纸箱','一般不符合项','整改中'], remark: '共检查5项，4项符合、1项不符合；不符合项已建立整改记录并上传整改前照片。', photos: true, signatures: ['检查人：李敏','整改人：赵海峰','审核人：陈军'] }
}

const docType = ref('purchaseOrder')
const doc = computed(() => documents[docType.value])

const sections = ref({ basic: true, details: true, photos: true, remark: true, signatures: true, audit: true })
const sectionOpts = [
  { key: 'basic', label: '基本信息' },
  { key: 'details', label: '业务明细' },
  { key: 'photos', label: '现场 / 关联照片' },
  { key: 'remark', label: '备注与处理结果' },
  { key: 'signatures', label: '审核签章' },
  { key: 'audit', label: '编号、生成时间与校验值' }
]

function onPdf() { ElMessage.success('PDF 已导出, 校验值已记录 (mock)') }
function onPrint() {
  printing.value = true
  setTimeout(() => {
    printing.value = false
    ElMessage.success('已发送「' + doc.value.title + '」到默认打印机, 共 ' + copies.value + ' 份 (mock)')
  }, 600)
}
</script>

<style scoped lang="scss">
.xk-print { display: flex; flex-direction: column; gap: 16px; }

// Figma .print-workspace
.print-workspace { display: grid; grid-template-columns: 260px 1fr; gap: 18px; align-items: start; }
@media (max-width: 1050px) { .print-workspace { grid-template-columns: 1fr; } }

.print-settings {
  padding: 20px; position: sticky; top: 88px;
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.ps-title { font-size: 15px; font-weight: 730; color: var(--text); }
.ps-intro { font-size: 12px; color: var(--muted); line-height: 1.55; margin: 6px 0 18px; }
.ps-field { margin-bottom: 14px; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.ps-field > label { font-size: 12px; color: #526174; font-weight: 650; }
.ps-section-title {
  padding-top: 13px; margin: 2px 0 7px; border-top: 1px solid var(--line);
  color: #4c5a6c; font-size: 11px; font-weight: 700;
}
.print-options { display: flex; flex-direction: column; margin-bottom: 15px; }
.print-options label {
  min-height: 35px; display: flex; align-items: center; gap: 8px;
  border-bottom: 1px solid #edf1f4; color: #59687a; font-size: 12px; cursor: pointer;
  &:last-child { border-bottom: 0; }
}
.print-two-fields { display: grid; grid-template-columns: 1fr 72px; gap: 8px; }
.ps-note {
  margin-top: 14px; background: #f5f8fb; border-radius: 9px; padding: 13px;
  strong { font-size: 12px; color: #526275; font-weight: 700; display: block; }
  p { font-size: 11px; color: var(--muted); line-height: 1.6; margin: 5px 0 0; }
}

// Figma .paper-wrap + .paper
.paper-wrap {
  padding: 35px; background: #dce3ea; border-radius: 14px; overflow: auto;
  display: flex; justify-content: center;
}
.paper {
  width: 680px; min-height: 900px; margin: auto; padding: 44px 48px 30px;
  background: #fff; box-shadow: 0 10px 30px rgba(28, 48, 69, .14); color: #182331;
  font-family: -apple-system, "PingFang SC", sans-serif; font-size: 13px; position: relative;
}
.size-A4L .paper { width: 900px; min-height: 620px; }

.paper-brand { display: flex; align-items: center; gap: 10px; }
.brand-symbol {
  display: inline-flex; width: 34px; height: 34px; padding: 7px; gap: 3px;
  align-items: flex-end; border-radius: 9px; background: var(--primary-color);
  i { flex: 1; background: #fff; border-radius: 2px 2px 0 0; transform: skewY(-18deg); }
  i:nth-child(1) { height: 50%; } i:nth-child(2) { height: 72%; } i:nth-child(3) { height: 94%; }
}
.paper-brand > div { flex: 1; }
.paper-brand strong { display: block; font-size: 14px; font-weight: 750; color: var(--text); }
.paper-brand small { display: block; font-size: 8px; letter-spacing: .13em; color: #748194; }

.paper-title {
  text-align: center; font-size: 22px; font-weight: 800; letter-spacing: .1em; margin: 30px 0 8px; color: var(--text);
}
.paper-code { text-align: center; color: #687588; font-size: 11px; margin: 0 0 24px; }

.paper-info {
  border: 1px solid #cfd6dd; display: grid; grid-template-columns: repeat(4, 1fr); margin-bottom: 15px;
  > div { padding: 9px 10px; border-right: 1px solid #cfd6dd; &:last-child { border-right: 0; } }
  small { color: #758092; font-size: 11px; display: block; margin-bottom: 5px; }
  strong { font-size: 13px; color: var(--text); display: block; }
}

.paper-body table { width: 100%; border-collapse: collapse; font-size: 12px; }
.paper-body th, .paper-body td { border: 1px solid #cfd6dd; padding: 9px; text-align: left; }
.paper-body th { background: #f0f3f6; font-weight: 600; }

.paper-section-title {
  font-size: 12px; font-weight: 750; margin: 18px 0 10px; padding-left: 8px; border-left: 3px solid var(--primary-color);
}
.paper-photo-block { margin-top: 18px; }
.paper-photos { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.paper-photos figure { margin: 0; border: 1px solid #d3dae1; padding: 6px; }
.paper-photos img { width: 100%; height: 145px; object-fit: cover; display: block; }
.paper-photos figcaption { color: #687588; text-align: center; font-size: 11px; margin: 5px 0 1px; }
.paper-no-photo {
  min-height: 58px; margin-top: 18px; display: flex; align-items: center; justify-content: center; gap: 7px;
  border: 1px dashed #ccd5df; color: #84909f; background: #f8fafc; font-size: 12px;
  .el-icon { font-size: 18px; }
}

.paper-remark {
  background: #f4f6f8; border: 1px solid #d8dee5; padding: 12px; margin-top: 18px;
  font-size: 12px; line-height: 1.7;
  strong { color: var(--primary-color); }
}
.paper-sign {
  display: flex; justify-content: space-between; padding: 30px 5px; font-size: 12px; color: var(--text); flex-wrap: wrap; gap: 10px;
}
.paper-footer {
  border-top: 1px solid #cfd6dd; text-align: center; color: #8792a0; font-size: 10px; padding-top: 9px; margin-top: 18px;
}

// 打印时隐藏壳、只留纸张
@media print {
  .xk-page-heading, .xk-compliance-strip, .print-settings { display: none !important; }
  .print-workspace { display: block; }
  .paper-wrap { background: #fff; padding: 0; border-radius: 0; }
  .paper { box-shadow: none; margin: 0 auto; width: 100%; min-height: 0; }
}
</style>
