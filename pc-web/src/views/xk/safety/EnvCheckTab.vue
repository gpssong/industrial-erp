<template>
  <div class="env-layout" v-loading="envLoading">
    <!-- 顶部: 发现安全隐患 banner (有 HIGH/MEDIUM 风险时显示) -->
    <div v-if="envAlertCount > 0" class="env-banner">
      <el-icon class="env-banner-ic"><WarningFilled /></el-icon>
      <div class="env-banner-body">
        <b>发现 {{ envAlertCount }} 项安全隐患</b>
        <small>请针对发现的隐患上传整改前后照片，形成检查闭环</small>
      </div>
      <span class="env-banner-no">{{ envSel?.checkNo || '未保存' }}</span>
    </div>

    <!-- 单卡: 检查清单 (GB 31603 逐项 + 工序专项) + 卫生问题记录 + 保存动作 -->
    <div class="env-card">
      <!-- 检查项目 head -->
      <div class="env-checklist-head">
        <div>
          <b>XK · 生产环境检查项目</b>
          <small>GB 31603-2015 食品接触材料生产通用卫生规范 · 10 项通用 + 1 项工序专项</small>
        </div>
        <div class="env-head-right">
          <el-radio-group v-model="envStage" size="small" @change="onEnvStageChange">
            <el-radio-button value="BLOW">吹膜</el-radio-button>
            <el-radio-button value="BAG">制袋</el-radio-button>
          </el-radio-group>
          <span class="env-checklist-score">完成 {{ envCheckedCount }}/{{ envItems.length }}</span>
        </div>
      </div>

      <!-- 逐项检查 (清单来自后端 /xk/env-check/template, 与 App 开工确认同一份) -->
      <div v-loading="envTemplateLoading" class="env-items">
        <div v-for="(it, i) in envItems" :key="it.code" :class="['env-row', it.status === 'ISSUE' ? 'issue' : 'pass']">
          <span class="env-no">{{ String(i + 1).padStart(2, '0') }}</span>
          <span class="env-text">
            {{ it.name }}
            <small class="env-req">{{ it.requirement }}</small>
            <small class="env-basis">{{ it.basis }}</small>
            <el-input v-if="it.status === 'ISSUE'" v-model="it.note" size="small" class="env-item-note"
                      placeholder="不符合情况说明 (必填)" />
          </span>
          <span class="env-verdict">
            <span class="env-v-label" :class="it.status === 'PASS' ? 'ok' : 'bad'">{{ it.status === 'PASS' ? '符合' : '发现问题' }}</span>
            <!-- 开=符合(PASS), 关=发现问题(ISSUE). 任意项关 → 触发下方整改表单; 全开 → 隐藏 -->
            <el-switch
              v-model="it.status"
              active-value="PASS"
              inactive-value="ISSUE"
              @change="onEnvItemToggle(it)"
            />
          </span>
        </div>
      </div>

      <!-- 卫生问题记录表单 (有 ISSUE 项时显示, 数据落 xk_env_check 整改字段) -->
      <div v-if="hasIssue" class="env-rectify-card">
        <div class="env-rectify-head">
          <span class="env-rectify-num">!</span>
          <div>
            <b>卫生问题整改记录</b>
            <small>{{ envIssueSummary }}</small>
          </div>
        </div>

        <div class="env-form">
          <div class="env-field">
            <label>问题等级 <i>*</i></label>
            <el-select v-model="rectifyForm.level" style="width:100%">
              <el-option label="一般不符合项" value="LOW" />
              <el-option label="严重不符合项" value="MEDIUM" />
              <el-option label="立即停产整改" value="HIGH" />
            </el-select>
          </div>
          <div class="env-field">
            <label>整改负责人 <i>*</i></label>
            <el-input v-model="rectifyForm.owner" placeholder="如: 赵海峰" />
          </div>
          <div class="env-field">
            <label>整改期限 <i>*</i></label>
            <el-date-picker v-model="rectifyForm.deadline" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </div>
          <div class="env-field">
            <label>整改状态</label>
            <el-select v-model="rectifyForm.rectifyStatus" style="width:100%">
              <el-option label="整改中" value="IN_PROGRESS" />
              <el-option label="已完成" value="DONE" />
            </el-select>
          </div>
          <div class="env-field env-field-full">
            <label>整改说明</label>
            <el-input v-model="rectifyForm.rectifyDesc" type="textarea" :rows="3" placeholder="描述整改措施、周期、复核等整改过程与结果" />
          </div>

          <!-- 2 块照片上传 (整改前 / 整改后) -->
          <div class="env-photo-row">
            <div class="env-photo-block">
              <div class="env-photo-head">
                <div>
                  <b>整改前照片</b>
                  <small>支持拍照或从相册选择，提交后与单据数据绑定</small>
                </div>
                <span class="env-photo-count">{{ rectBeforeArr.length }}/6</span>
              </div>
              <div class="env-photo-grid">
                <div v-for="(p, i) in rectBeforeArr" :key="'b'+i" class="env-photo-item">
                  <el-image :src="p.url" :preview-src-list="rectBeforeArr.map(x => x.url)" fit="cover" />
                  <el-icon class="env-photo-lock"><Lock /></el-icon>
                  <el-button class="env-photo-del" size="small" type="danger" plain @click="rectBeforeArr.splice(i, 1)">删</el-button>
                </div>
                <div v-if="rectBeforeArr.length < 6" class="env-photo-add" @click="onUploadPhoto('before')">
                  <el-icon><Plus /></el-icon>
                  <span>拍照 / 上传</span>
                  <small>JPG, PNG</small>
                </div>
              </div>
            </div>
            <div class="env-photo-block">
              <div class="env-photo-head">
                <div>
                  <b>整改后照片</b>
                  <small>支持拍照或从相册选择，提交后与单据数据绑定</small>
                </div>
                <span class="env-photo-count">{{ rectAfterArr.length }}/6</span>
              </div>
              <div class="env-photo-grid">
                <div v-for="(p, i) in rectAfterArr" :key="'a'+i" class="env-photo-item">
                  <el-image :src="p.url" :preview-src-list="rectAfterArr.map(x => x.url)" fit="cover" />
                  <el-icon class="env-photo-lock"><Lock /></el-icon>
                  <el-button class="env-photo-del" size="small" type="danger" plain @click="rectAfterArr.splice(i, 1)">删</el-button>
                </div>
                <div v-if="rectAfterArr.length < 6" class="env-photo-add" @click="onUploadPhoto('after')">
                  <el-icon><Plus /></el-icon>
                  <span>拍照 / 上传</span>
                  <small>JPG, PNG</small>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部提示 + 保存动作 -->
        <div class="env-foot">
          <div class="env-foot-note"><el-icon><Lock /></el-icon>保存后写入审计日志，原始检查记录不可删除</div>
          <div class="env-foot-actions">
            <el-button @click="onSaveEnvRecord" :loading="envSaving">保存整改记录</el-button>
            <el-button type="primary" @click="onGenReport"><el-icon><Plus /></el-icon>生成检查报告</el-button>
          </div>
        </div>
      </div>

      <!-- 无问题时的提示 + 保存合格记录 (v1.1.108: 全合格也要能生成并归档一条检查记录) -->
      <div v-else class="env-clean-tip">
        <el-icon color="var(--success)"><Check /></el-icon>
        <span>当前检查表全部符合, 无需整改</span>
        <div class="env-clean-actions" style="margin-left:auto">
          <el-button @click="onGenReport"><el-icon><Document /></el-icon>生成检查报告</el-button>
          <el-button type="primary" :loading="envSaving" @click="onSaveEnvPass">
            <el-icon><Check /></el-icon>保存合格检查记录
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { WarningFilled, Lock, Plus, Check, Document } from '@element-plus/icons-vue'
import { envCheckApi } from '@/api/xk'
import { useUserStore } from '@/store/user'
import { pickImageFile } from '@/utils/upload'

const userStore = useUserStore()
const currentUserName = () => userStore.userInfo?.nickname || userStore.userInfo?.username || ''

// ============== 生产环境检查 (xk_env_check, GB 31603-2015 逐项清单) ==============
const envLoading = ref(false)
const envSel = ref(null)         // 当前正在编辑的检查单 (DRAFT) 或 null (新建未保存)
const envSaving = ref(false)

// v1.1.151: 清单来自后端唯一模板 (与 App 开工确认同一份), 按工序区分吹膜/制袋专项项.
// 每项: { code, name, requirement, basis, envType, status: 'PASS'|'ISSUE', note }
const envStage = ref('BLOW')
const envTemplateLoading = ref(false)
const envItems = ref([])
const STAGE_LABEL = { BLOW: '吹膜', BAG: '制袋' }

async function loadEnvTemplate(stage = envStage.value) {
  envTemplateLoading.value = true
  try {
    const r = await envCheckApi.template(stage)
    if (r.code === 200) {
      envItems.value = (r.data?.items || []).map(it => ({ ...it, status: 'PASS', note: '' }))
    } else {
      ElMessage.error(r.msg || '检查项加载失败')
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '检查项加载失败')
  } finally { envTemplateLoading.value = false }
}
function onEnvStageChange() { loadEnvTemplate(envStage.value) }

const hasIssue = computed(() => envItems.value.some(i => i.status === 'ISSUE'))
const issues = computed(() => envItems.value.filter(i => i.status === 'ISSUE'))
const envCheckedCount = computed(() => envItems.value.filter(i => i.status === 'PASS').length)
// 隐患数 = 发现问题的检查项数
const envAlertCount = computed(() => issues.value.length)
// 整改卡片摘要: 逐条列出本次不符合项 + 现场说明 (无 mock 兜底文案)
const envIssueSummary = computed(() => issues.value.length
  ? issues.value.map((i, n) => (n + 1) + '. ' + i.name + (String(i.note || '').trim() ? '：' + i.note.trim() : '')).join('；')
  : '')

// 检查项开关切换 — 关掉某项(设为"发现问题")自动弹整改表单; 全符合时由 hasIssue 收敛表单
function onEnvItemToggle(it) {
  if (it.status === 'ISSUE') {
    ElMessage.warning('第 ' + (envItems.value.indexOf(it) + 1) + ' 项发现问题, 请填写情况说明与整改记录')
  }
}

// 台账快照: 表头 + 逐项结论 (逐项留痕, XK 现场核查直接对表)
function envSnapshotText() {
  const head = 'GB 31603-2015 生产环境检查（' + (STAGE_LABEL[envStage.value] || '吹膜')
    + '开工前, 本记录 ' + envItems.value.length + ' 项）'
  const lines = envItems.value.map((it, n) => (n + 1) + '. ' + it.name + ' — '
    + (it.status === 'PASS' ? '符合' : '不符合' + (String(it.note || '').trim() ? '（' + it.note.trim() + '）' : '')))
  return head + '\n' + lines.join('\n')
}

// 整改表单 (与 image #6 对齐, 数据走 xk_env_check 整改字段)
const rectifyForm = ref({
  level: 'LOW', owner: '', deadline: '', rectifyDesc: '',
  rectifyStatus: 'IN_PROGRESS',
  beforePhotos: [], afterPhotos: []
})

// 照片上传 → 真 /system/upload/file, 返回 URL 回填照片数组
function onUploadPhoto(side) {
  const arr = side === 'before' ? rectBeforeArr : rectAfterArr
  if (arr.value.length >= 6) { ElMessage.warning('最多 6 张照片'); return }
  pickImageFile((url, name) => arr.value.push({ id: 'up' + Date.now(), url, name }))
}

// 保存整改记录 → POST /xk/env-check/save (新建 DRAFT) 或 /xk/env-check/{id}/check (提交整改)
// v1.1.108 hotfix-3: 后端 XkEnvCheck.plannedAt 字段 @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss", timezone="GMT+8")
//   强制 Jackson 用该格式反序列化 — 空格分隔 + 含秒。前端发 ISO 'T' 分隔(2026-10-20T09:18:47) 仍 500.
//   改发 'YYYY-MM-DD HH:mm:ss' 空格分隔且含秒的北京时区时间, 与 @JsonFormat 严格对齐。
//   (修前 v1.1.108 hotfix 用 .slice(0,16).replace('T',' ') 缺秒, hotfix-2 用 .slice(0,19) 是 'T' 分隔 — 两个都不对)
// helper: 取当前北京时间的 'YYYY-MM-DD HH:mm:ss' 字符串 (HH:mm:ss 三段都补 0)
function nowBeijingStr() {
  const d = new Date()
  // Date.toLocaleString('zh-CN', {timeZone:'Asia/Shanghai', hour12:false}) → "2026/10/20 09:18:47"
  const s = d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
  return s.replace(/\//g, '-')
}

// v1.1.108 hotfix-4: MySQL JSON 列不接受空字符串 '' (报 "Invalid JSON text: The document is empty"),
//   必须发合法的 JSON 文本 — 空数组 '[]' 或 null. 后端 X6 (StrUtil.isBlank) 对 '[]' 返回 false (有内容),
//   MEDIUM/HIGH 照片必填门禁仍能正确触发. 整改分支用 JSON.stringify(arr || []) 已合法, 不动;
//   全合格分支原写 '' 必须改成 '[]'.
const EMPTY_JSON_ARR = '[]'

async function onSaveEnvRecord() {
  if (!rectifyForm.value.owner) { ElMessage.warning('请填写整改负责人'); return }
  if (!rectifyForm.value.deadline) { ElMessage.warning('请选择整改期限'); return }
  const noNote = issues.value.filter(i => !String(i.note || '').trim())
  if (noNote.length) {
    ElMessage.warning('请填写不符合情况说明: ' + noNote.map(i => i.name).join('、'))
    return
  }
  envSaving.value = true
  try {
    const payload = {
      ...rectifyForm.value,
      beforePhotos: JSON.stringify(rectifyForm.value.beforePhotos || []),
      afterPhotos: JSON.stringify(rectifyForm.value.afterPhotos || []),
      workshop: STAGE_LABEL[envStage.value] + '车间',
      envType: 'CLEANLINESS',
      checkTitle: STAGE_LABEL[envStage.value] + '车间 生产环境检查 · 不合格待整改',
      checkContent: envSnapshotText(),
      // v1.1.108 hotfix-3: plannedAt 用 nowBeijingStr() 输出 'YYYY-MM-DD HH:mm:ss' 匹配 @JsonFormat
      plannedAt: nowBeijingStr()
    }
    let r
    if (envSel.value && envSel.value.id) {
      // 已有 DRAFT, 走 /check 提交整改
      r = await envCheckApi.check(envSel.value.id, payload)
      if (r.code === 200) {
        ElMessage.success('整改记录已提交')
        // 把整改状态从 IN_PROGRESS 推为 DONE
        envItems.value = envItems.value.map(i => ({ ...i, status: 'PASS' }))
        loadEnv()
      } else ElMessage.error(r.msg || '提交失败')
    } else {
      // 无 DRAFT, 先 save 再 check (两步)
      r = await envCheckApi.save(payload)
      if (r.code === 200 && r.data) {
        envSel.value = r.data
        await envCheckApi.check(r.data.id, payload)
        ElMessage.success('已新建检查单并提交整改')
        envItems.value = envItems.value.map(i => ({ ...i, status: 'PASS' }))
        loadEnv()
      } else ElMessage.error(r.msg || '新建失败')
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally { envSaving.value = false }
}

// 生成检查报告 (占位, 后续接 print/report)
function onGenReport() {
  ElMessage.success('检查报告已生成 (占位)')
}

// 全合格场景保存检查记录: 11 项全 PASS 时归档一条 xk_env_check,
// 走 save(建 DRAFT) → check(level=null, rectifyStatus=DONE); level 留空 = 全合格.
// 检查项快照 (表头 + 逐项"符合") 写进 checkContent, 台账里能看到本次查了哪些项.
async function onSaveEnvPass() {
  if (hasIssue.value) {
    ElMessage.warning('存在不符合项, 请填写整改记录后提交, 不能按「全部合格」归档')
    return
  }
  const snapshot = envSnapshotText()
  envSaving.value = true
  try {
    const payload = {
      level: null,                          // 全合格, 无问题等级
      rectifyOwner: '', deadline: '', rectifyDesc: '',
      rectifyStatus: 'DONE',
      workshop: STAGE_LABEL[envStage.value] + '车间',
      envType: 'CLEANLINESS',
      checkTitle: STAGE_LABEL[envStage.value] + '车间 生产环境检查 · 全部合格',
      checkContent: snapshot + '\n检查人=' + (currentUserName() || '-') + ', ' + envItems.value.length + ' 项逐项合格, 无需整改。',
      // v1.1.108 hotfix-3: plannedAt 匹配 @JsonFormat 格式
      plannedAt: nowBeijingStr(),
      // v1.1.108 hotfix-4: MySQL JSON 列不接受 '' — 发空数组 '[]' (合法的 JSON 文档)
      beforePhotos: EMPTY_JSON_ARR, afterPhotos: EMPTY_JSON_ARR
    }
    let r
    if (envSel.value && envSel.value.id) {
      // 已有 DRAFT, 直接 /check 归档为全合格
      r = await envCheckApi.check(envSel.value.id, payload)
      if (r.code === 200) { ElMessage.success('合格检查记录已归档'); await loadEnv() }
      else ElMessage.error(r.msg || '保存失败')
    } else {
      // 无 DRAFT: save 建单再 check 归档 (两步)
      r = await envCheckApi.save(payload)
      if (r.code === 200 && r.data) {
        const cr = await envCheckApi.check(r.data.id, payload)
        if (cr.code === 200) { ElMessage.success('已新建并归档合格检查记录 (' + (r.data.checkNo || '')); await loadEnv() }
        else ElMessage.error(cr.msg || '归档失败')
      } else ElMessage.error(r.msg || '新建失败')
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally { envSaving.value = false }
}

async function loadEnv() {
  envLoading.value = true
  try {
    const r = await envCheckApi.page({ pageNum: 1, pageSize: 20 })
    if (r.code === 200) {
      const list = r.data?.records || []
      // 选第一条 DRAFT 作为当前编辑对象; 没有则 null (新建未保存)
      envSel.value = list.find(e => e.status === 'DRAFT') || null
    }
    // v1.1.151: 检查清单首次进入时从后端拉取 (切工序时由 onEnvStageChange 重拉)
    if (!envItems.value.length) await loadEnvTemplate()
  } finally { envLoading.value = false }
}

// 整改照片 (与首检/成品的 fin/semi 照片分离)
const rectBeforeArr = ref([])
const rectAfterArr = ref([])

onMounted(loadEnv)
// 父组件每次切回本 tab 都要重拉 DRAFT (可能已被 App 开工确认归档)
defineExpose({ reload: loadEnv })
</script>

<style scoped lang="scss">
.env-layout { display: flex; flex-direction: column; gap: 16px; }
.env-main { display: flex; flex-direction: column; gap: 16px; }

/* ===== image #6: 顶部安全隐患 banner ===== */
.env-banner {
  display: flex; align-items: center; gap: 14px;
  background: #fff7e8; border: 1px solid #f6d795; border-radius: 14px;
  padding: 14px 18px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.env-banner-ic { color: #e8a02a; font-size: 22px; flex: 0 0 auto; }
.env-banner-body { flex: 1; min-width: 0; }
.env-banner-body b { font-size: 14px; font-weight: 730; color: #8a5a0f; display: block; }
.env-banner-body small { display: block; font-size: 12px; color: #a88435; margin-top: 3px; }
.env-banner-no { font-family: "SF Mono", Menlo, monospace; font-size: 12px; color: #8a5a0f; font-weight: 700; }

/* ===== image #6: 单卡 (检查清单 + 卫生问题记录) ===== */
.env-card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 20px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.env-checklist-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; }
.env-checklist-head b { font-size: 15px; font-weight: 730; color: var(--text); display: block; }
.env-checklist-head small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
.env-checklist-score { color: var(--success); font-size: 12px; font-weight: 700; white-space: nowrap; }
// v1.1.151: 工序切换 (吹膜/制袋) + 逐项判定要点与条款号
.env-head-right { display: flex; align-items: center; gap: 12px; flex: 0 0 auto; }
.env-req { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; line-height: 1.5; }
.env-basis { display: block; font-size: 11px; color: #b1b3b8; margin-top: 1px; }
.env-item-note { margin-top: 6px; max-width: 460px; }
.env-items { display: flex; flex-direction: column; }

.env-row {
  display: flex; align-items: center; gap: 14px; padding: 14px 4px;
  border-bottom: 1px solid #eef1f4;
  &:last-child { border-bottom: 0; }
  &.issue { background: #fff7f6; margin: 0 -14px; padding: 14px 14px; }
}
.env-no { color: #9ba7b5; font-size: 12px; font-weight: 700; flex: 0 0 auto; width: 22px; }
.env-row.issue .env-no { color: var(--danger); }
.env-text { flex: 1; min-width: 0; font-size: 14px; color: var(--text); }
.env-verdict { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; flex: 0 0 auto; }
.env-row.pass .env-verdict { color: var(--success); }
.env-row.issue .env-verdict { color: var(--danger); }
.env-v-ic { font-size: 15px; }
// v1.1.108: 检查项右侧开关 — 左侧文字标签(符合/发现问题)按状态着色, 开关 inline-prompt 显示 符合/问题
.env-v-label { font-size: 12px; font-weight: 700; min-width: 40px; text-align: right; }
.env-v-label.ok { color: var(--success); }
.env-v-label.bad { color: var(--danger); }
.env-row :deep(.el-switch) { --switch-on-color: #1f7a33; }
.env-row :deep(.el-switch.is-checked .el-switch__core) { background-color: #1f7a33; border-color: #1f7a33; }
.env-row :deep(.el-switch .el-switch__core) { min-width: 52px; }

// 卫生问题整改记录 (嵌在 env-card 内部, 去掉独立阴影)
.env-rectify-card {
  background: #fff; border: 0; border-radius: 0; padding: 0;
  margin-top: 20px; box-shadow: none;
}
.env-rectify-head { display: flex; align-items: center; gap: 14px; padding-bottom: 16px; margin-bottom: 18px; border-bottom: 1px solid #f0f3f7; }
.env-rectify-num {
  width: 34px; height: 34px; flex: 0 0 auto; border-radius: 9px;
  background: #fdeeeb; color: var(--danger); display: grid; place-items: center; font-size: 17px; font-weight: 800;
}
.env-rectify-head b { font-size: 15px; font-weight: 730; color: var(--text); display: block; }
.env-rectify-head small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }

.env-form { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 24px; }
@media (max-width: 900px) { .env-form { grid-template-columns: 1fr; } }
.env-field { display: flex; flex-direction: column; gap: 6px; }
.env-field.env-field-full { grid-column: 1 / -1; }
.env-field > label { font-size: 13px; color: var(--text); font-weight: 550; }
.env-field > label i { color: var(--danger); font-style: normal; margin-left: 2px; }

// 整改前/后 照片
.env-photo-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 20px; }
@media (max-width: 900px) { .env-photo-row { grid-template-columns: 1fr; } }
.env-photo-block { background: #fbfcfe; border: 1px solid var(--line); border-radius: 12px; padding: 16px; }
.env-photo-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 12px; }
.env-photo-head b { font-size: 13px; font-weight: 700; color: var(--text); display: block; }
.env-photo-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.env-photo-count { font-size: 11px; color: var(--muted); font-weight: 700; white-space: nowrap; }
.env-photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
@media (max-width: 1200px) { .env-photo-grid { grid-template-columns: repeat(2, 1fr); } }
.env-photo-item {
  position: relative; aspect-ratio: 1.1; border-radius: 9px; overflow: hidden;
  background: #e9eef3; border: 1px solid #dfe6ef;
  :deep(.el-image) { width: 100%; height: 100%; display: block; }
  .env-photo-lock { position: absolute; top: 6px; right: 6px; width: 21px; height: 21px; background: rgba(8, 20, 34, .78); color: #fff; border-radius: 50%; display: grid; place-items: center; font-size: 11px; }
}
.env-photo-add {
  aspect-ratio: 1.1; border: 1px dashed #aebdcc; border-radius: 9px; background: #f8fafc;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
  cursor: pointer; color: var(--muted);
  .el-icon { color: var(--primary-color); font-size: 20px; }
  strong { font-size: 11px; }
  small { font-size: 9px; color: #9aa7b5; }
  &:hover { border-color: var(--primary-color); color: var(--primary-color); }
}
.env-photo-del {
  position: absolute; bottom: 6px; right: 6px;
  z-index: 2;
}

.env-binding-note { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #607189; margin-top: 12px; }
.env-binding-note .el-icon { color: var(--primary-color); }

/* ===== image #6: 全符合时的提示条 (v1.1.108 加保存合格记录按钮, 撑满一行 + 右侧动作区) ===== */
.env-clean-tip {
  display: flex; align-items: center; gap: 8px;
  margin-top: 18px; padding: 14px 18px;
  background: #f0f9f1; border: 1px solid #c6e6c8; border-radius: 12px;
  color: #1f7a33; font-size: 13px; font-weight: 600;
}
.env-clean-tip .el-icon { font-size: 16px; }

/* ===== image #6: 底部动作条 ===== */
// 底部动作 (image #10)
.env-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 14px 20px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.env-foot-note { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #607189; }
.env-foot-note .el-icon { color: #9aa7b5; }
.env-foot-actions { display: flex; gap: 10px; }
@media (max-width: 700px) { .env-foot { flex-direction: column; align-items: flex-start; } }
</style>
