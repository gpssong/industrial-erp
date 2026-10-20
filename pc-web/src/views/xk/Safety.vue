<template>
  <div class="xk-safety">
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产与质量 / XK 质量管控</div>
        <h1 class="xk-title">质量检验与XK检查</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onExport"><el-icon><Download /></el-icon>导出质量档案</el-button>
        <el-button type="primary" @click="onNew"><el-icon><Plus /></el-icon>新建检验</el-button>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>食品袋执行原料、PE膜半成品、PE袋成品三级检验；检验设备必须在有效校准周期内并留存校准报告</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <!-- 5 个 tab -->
    <el-tabs v-model="tab" class="xk-tabs">
      <!-- 1. PE膜半成品检验 (默认, 对齐 image #1) -->
      <el-tab-pane label="PE膜半成品检验" name="semi">
        <div class="semi-layout">
          <div class="semi-main">
            <!-- 记录选择列表 (从 inspectionApi.page FAI 拉取; 点击填充右侧表单) -->
            <div class="rec-list-card">
              <div class="rec-list-head"><b>待检 / 已检记录</b><small>点击记录查看并判定</small></div>
              <div class="rec-list" v-loading="false">
                <div v-for="row in semiList" :key="row.id" class="rec-list-item"
                  :class="{ active: semiSel?.id === row.id }" @click="onSelectSemi(row)">
                  <div class="rli-top">
                    <strong>{{ row.billNo || ('#' + row.id) }}</strong>
                    <el-tag :type="row.result === 'PASS' ? 'success' : row.result === 'FAIL' ? 'danger' : 'info'" size="small" effect="light">{{ row.result || '待判定' }}</el-tag>
                  </div>
                  <div class="rli-sub">{{ row.productName || row.batchNo || '—' }}<span v-if="row.batchNo"> · 批号 {{ row.batchNo }}</span></div>
                </div>
                <div v-if="!semiList.length" class="rec-list-empty">暂无半成品检验记录</div>
              </div>
            </div>

            <!-- 记录头卡 -->
            <div class="rec-head">
              <div class="rec-head-l">
                <el-tag size="small" effect="plain" round>半成品检验</el-tag>
                <h2>{{ semiSel ? semiSel.productName : 'PE 吹膜半成品检验' }}</h2>
                <small>记录编号 · {{ semiSel?.billNo || '未选择' }}</small>
              </div>
              <el-tag :type="semiSel?.result === 'PASS' ? 'success' : semiSel?.result === 'FAIL' ? 'danger' : 'info'" size="small" effect="light">
                {{ semiSel?.result || '待选' }}
              </el-tag>
            </div>

            <!-- 主信息 2×4 表单 (从 semiSel 拉) -->
            <div class="form-card">
              <div class="form-grid">
                <div class="field"><label>半成品批号 <i>*</i></label><el-input :model-value="semiSel?.batchNo || ''" readonly class="f-readonly" placeholder="点击上方记录" /></div>
                <div class="field"><label>关联生产工单 <i>*</i></label><el-input :model-value="semiSel?.sourceBillNo || '-'" readonly class="f-readonly" /></div>
                <div class="field"><label>产品名称 <i>*</i></label><el-input :model-value="semiSel?.productName || ''" readonly class="f-readonly" /></div>
                <div class="field"><label>抽样数量 <i>*</i></label><el-input :model-value="(semiSel?.sampleQty ?? '-')" readonly class="f-readonly" /></div>
                <div class="field"><label>检验人员 <i>*</i></label><el-input v-model="inspector" readonly class="f-readonly" /></div>
                <div class="field"><label>检验时间 <i>*</i></label><el-input :model-value="semiSel?.checkTime || semiSel?.createTime || '-'" readonly class="f-readonly" /><small class="field-hint">系统时间, 不可修改</small></div>
              </div>
            </div>

            <!-- 检验项目表 -->
            <div class="check-table-card">
              <div class="ct-head">
                <div class="ct-col c-item">检验项目</div>
                <div class="ct-col c-req">技术要求</div>
                <div class="ct-col c-method">检验方法</div>
                <div class="ct-col c-result">实测结果</div>
                <div class="ct-col c-verdict">判定</div>
              </div>
              <div class="ct-body">
                <div v-for="r in semiItems" :key="r.item || r.name" class="ct-row">
                  <div class="ct-col c-item">{{ r.item || r.name }}</div>
                  <div class="ct-col c-req">{{ r.req || r.std }}</div>
                  <div class="ct-col c-method">{{ r.method }}</div>
                  <div class="ct-col c-result">{{ r.result || '-' }}</div>
                  <div class="ct-col c-verdict">
                    <el-tag :type="r.ok === false ? 'danger' : 'success'" size="small" effect="light">{{ r.ok === false ? '不合格' : '合格' }}</el-tag>
                  </div>
                </div>
                <div v-if="!semiItems.length" class="empty-row">无检验项目数据, 请新建检验</div>
              </div>
            </div>

            <!-- 抽样与仪器照片 -->
            <div class="photo-card">
              <div class="photo-card-head">
                <div>
                  <b>半成品抽检与仪器照片</b>
                  <small>支持拍照或从相册选择, 提交后与单据强绑定</small>
                </div>
                <span class="xk-badge" :class="semiPhotos.length ? 'xk-badge-green' : 'xk-badge-gray'">{{ semiPhotos.length }}/6</span>
              </div>
              <div class="photo-grid">
                <div v-for="p in semiPhotos" :key="p.id" class="photo-item">
                  <img :src="p.url" :alt="p.name" />
                  <el-icon class="photo-lock"><Lock /></el-icon>
                </div>
                <div v-for="i in (6 - semiPhotos.length)" :key="`s-empty-${i}`" class="photo-item empty" @click="onAddPhoto('semi')">
                  <span class="camera-circle"><el-icon><Camera /></el-icon></span>
                  <strong>拍照 / 上传</strong>
                  <small>JPG, PNG</small>
                </div>
              </div>
              <div class="binding-note"><el-icon><Lock /></el-icon>附件随业务单据归档, 不支持脱离单据单独删除</div>
            </div>

            <!-- 底部动作 -->
            <div class="semi-foot">
              <el-button type="primary" :loading="semiSubmitting" @click="onSaveSemi" :disabled="!semiSel"><el-icon><Check /></el-icon>保存检验记录 (PASS)</el-button>
              <el-button @click="onOpenReport(semiSel)" :disabled="!semiSel"><el-icon><Document /></el-icon>查看详情</el-button>
            </div>
          </div>

          <!-- 右侧 综合判定 + 留样管理 -->
          <div class="semi-side">
            <div class="verdict-card">
              <div class="verdict-icon"><el-icon :size="22"><CircleCheckFilled /></el-icon></div>
              <div class="verdict-body">
                <b>综合判定：合格</b>
                <p>半成品可进入制袋工序，并保留对应批次检验记录。</p>
              </div>
              <div class="verdict-meta">
                <div><small>检验依据</small><span>内控规范 Q/H 02</span></div>
                <div><small>关联批次</small><span>原料 2 批 · 成品 1 批</span></div>
                <div><small>报告状态</small><span>待签发</span></div>
              </div>
            </div>

            <div class="retain-card">
              <b>留样管理</b>
              <div class="retain-row"><small>留样编号</small><span>LY-BPC-0618-16</span></div>
              <div class="retain-row"><small>留样位置</small><span>质量室 / 留样柜 A-06</span></div>
              <div class="retain-row"><small>留样期限</small><span>24 个月</span></div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 2. PE袋成品检验 (对齐 image #5-#7) -->
      <el-tab-pane label="PE袋成品检验" name="finished">
        <div class="semi-layout">
          <div class="semi-main">
            <!-- 记录选择列表 (OQC; 点击填充右侧表单) -->
            <div class="rec-list-card">
              <div class="rec-list-head"><b>待检 / 已检记录</b><small>点击记录查看并判定</small></div>
              <div class="rec-list">
                <div v-for="row in finList" :key="row.id" class="rec-list-item"
                  :class="{ active: finSel?.id === row.id }" @click="onSelectFin(row)">
                  <div class="rli-top">
                    <strong>{{ row.billNo || ('#' + row.id) }}</strong>
                    <el-tag :type="row.result === 'PASS' ? 'success' : row.result === 'FAIL' ? 'danger' : 'info'" size="small" effect="light">{{ row.result || '待判定' }}</el-tag>
                  </div>
                  <div class="rli-sub">{{ row.productName || row.batchNo || '—' }}<span v-if="row.batchNo"> · 批号 {{ row.batchNo }}</span></div>
                </div>
                <div v-if="!finList.length" class="rec-list-empty">暂无成品检验记录</div>
              </div>
            </div>

            <!-- 记录头卡 -->
            <div class="rec-head">
              <div class="rec-head-l">
                <el-tag size="small" effect="plain" round>成品出厂检验</el-tag>
                <h2>{{ finSel ? finSel.productName : 'PE 食品袋成品检验' }}</h2>
                <small>记录编号 · {{ finSel?.billNo || '未选择' }}</small>
              </div>
              <el-tag :type="finSel?.result === 'PASS' ? 'success' : finSel?.result === 'FAIL' ? 'danger' : 'info'" size="small" effect="light">
                {{ finSel?.result || '待选' }}
              </el-tag>
            </div>

            <!-- 主信息 2×3 表单 (从 finSel 拉) -->
            <div class="form-card">
              <div class="form-grid">
                <div class="field"><label>成品批号 <i>*</i></label><el-input :model-value="finSel?.batchNo || ''" readonly class="f-readonly" placeholder="点击上方记录" /></div>
                <div class="field"><label>关联生产工单 <i>*</i></label><el-input :model-value="finSel?.sourceBillNo || '-'" readonly class="f-readonly" /></div>
                <div class="field"><label>产品名称 <i>*</i></label><el-input :model-value="finSel?.productName || ''" readonly class="f-readonly" /></div>
                <div class="field"><label>抽样数量 <i>*</i></label><el-input :model-value="(finSel?.sampleQty ?? '-')" readonly class="f-readonly" /></div>
                <div class="field"><label>检验人员 <i>*</i></label><el-input v-model="inspector" readonly class="f-readonly" /></div>
                <div class="field"><label>检验时间 <i>*</i></label><el-input :model-value="finSel?.checkTime || finSel?.createTime || '-'" readonly class="f-readonly" /><small class="field-hint">系统时间, 不可修改</small></div>
              </div>
            </div>

            <!-- 检验项目表 -->
            <div class="check-table-card">
              <div class="ct-head">
                <div class="ct-col c-item">检验项目</div>
                <div class="ct-col c-req">技术要求</div>
                <div class="ct-col c-method">检验方法</div>
                <div class="ct-col c-result">实测结果</div>
                <div class="ct-col c-verdict">判定</div>
              </div>
              <div class="ct-body">
                <div v-for="r in finItems" :key="r.item || r.name" class="ct-row">
                  <div class="ct-col c-item">{{ r.item || r.name }}</div>
                  <div class="ct-col c-req">{{ r.req || r.std }}</div>
                  <div class="ct-col c-method">{{ r.method }}</div>
                  <div class="ct-col c-result">{{ r.result || '-' }}</div>
                  <div class="ct-col c-verdict">
                    <el-tag :type="r.ok === false ? 'danger' : 'success'" size="small" effect="light">{{ r.ok === false ? '不合格' : '合格' }}</el-tag>
                  </div>
                </div>
                <div v-if="!finItems.length" class="empty-row">无检验项目数据, 请新建检验</div>
              </div>
            </div>

            <!-- 成品抽检与留样照片 -->
            <div class="photo-card">
              <div class="photo-card-head">
                <div>
                  <b>成品抽检与留样照片</b>
                  <small>支持拍照或从相册选择, 提交后与单据强绑定</small>
                </div>
                <span class="xk-badge" :class="finPhotos.length ? 'xk-badge-green' : 'xk-badge-gray'">{{ finPhotos.length }}/6</span>
              </div>
              <div class="photo-grid">
                <div v-for="p in finPhotos" :key="p.id" class="photo-item">
                  <img :src="p.url" :alt="p.name" />
                  <el-icon class="photo-lock"><Lock /></el-icon>
                </div>
                <div v-for="i in (6 - finPhotos.length)" :key="`f-empty-${i}`" class="photo-item empty" @click="onAddPhoto('fin')">
                  <span class="camera-circle"><el-icon><Camera /></el-icon></span>
                  <strong>拍照 / 上传</strong>
                  <small>JPG, PNG</small>
                </div>
              </div>
              <div class="binding-note"><el-icon><Lock /></el-icon>附件随业务单据归档, 不支持脱离单据单独删除</div>
            </div>

            <!-- 底部动作 -->
            <div class="semi-foot">
              <el-button type="primary" :loading="finSubmitting" @click="onSaveFin" :disabled="!finSel"><el-icon><Check /></el-icon>保存检验记录 (PASS)</el-button>
              <el-button @click="onOpenReport(finSel)" :disabled="!finSel"><el-icon><Document /></el-icon>查看详情</el-button>
            </div>
          </div>

          <!-- 右侧 综合判定 + 留样管理 -->
          <div class="semi-side">
            <div class="verdict-card">
              <div class="verdict-icon"><el-icon :size="22"><CircleCheckFilled /></el-icon></div>
              <div class="verdict-body">
                <b>综合判定: {{ finSel?.result === 'PASS' ? '合格' : finSel?.result === 'FAIL' ? '不合格' : '待判定' }}</b>
                <p>成品符合出厂要求, 可签发检验报告并办理入库。</p>
              </div>
              <div class="verdict-meta">
                <div><small>检验依据</small><span>GB 4806.7 / 产品标准</span></div>
                <div><small>关联批次</small><span class="mono">{{ finSel?.batchNo || '-' }}</span></div>
                <div><small>报告状态</small><span>{{ finSel?.status === 'CHECKED' ? '已签发' : '待签发' }}</span></div>
              </div>
            </div>

            <div class="retain-card">
              <b>留样管理</b>
              <div class="retain-row"><small>留样编号</small><span class="mono">LY-{{ finSel?.billNo || '---' }}</span></div>
              <div class="retain-row"><small>留样位置</small><span>质量室 / 留样柜 A-06</span></div>
              <div class="retain-row"><small>留样期限</small><span>24 个月</span></div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 2.5 过程巡检 (PAT) — 工序报工自动生成的巡检草稿, 展示工单/工序/车间/合格不良 + 审核冻结 -->
      <el-tab-pane label="过程巡检 (PAT)" name="pat">
        <div class="semi-layout">
          <div class="semi-main">
            <div class="rec-list-card">
              <div class="rec-list-head"><b>工序巡检记录</b><small>报工自动生成 · 点击判定/冻结</small></div>
              <div class="rec-list">
                <div v-for="row in patList" :key="row.id" class="rec-list-item"
                  :class="{ active: patSel?.id === row.id }" @click="onSelectPat(row)">
                  <div class="rli-top">
                    <strong>{{ row.sourceBillNo || ('#' + row.id) }}</strong>
                    <el-tag :type="row.result === 'PASS' ? 'success' : row.result === 'FAIL' || row.result === 'FAIL_REJECT' ? 'danger' : 'info'" size="small" effect="light">
                      {{ patResultLabel(row.result) }}
                    </el-tag>
                  </div>
                  <div class="rli-sub">
                    工序 [{{ row.workshop || '—' }}] · {{ row.productName || row.productCode || '—' }}
                    <span v-if="num(row.unqualifiedQty) > 0" style="color:#f56c6c"> · 不良 {{ num(row.unqualifiedQty) }}</span>
                  </div>
                </div>
                <div v-if="!patList.length" class="rec-list-empty">暂无过程巡检记录 (工单报工后自动生成)</div>
              </div>
            </div>

            <div class="rec-head">
              <div class="rec-head-l">
                <el-tag size="small" effect="plain" round>工序过程巡检</el-tag>
                <h2>{{ patSel ? (patSel.productName || patSel.productCode || '过程巡检') : '工序过程巡检' }}</h2>
                <small>工单 · {{ patSel?.sourceBillNo || '未选择' }} · 车间 {{ patSel?.workshop || '—' }}</small>
              </div>
              <el-tag :type="patSel?.result === 'PASS' ? 'success' : patSel?.result === 'FAIL' || patSel?.result === 'FAIL_REJECT' ? 'danger' : 'info'" size="small" effect="light">
                {{ patSel ? patResultLabel(patSel.result) : '待选' }}
              </el-tag>
            </div>

            <div class="form-card">
              <div class="form-grid">
                <div class="field"><label>关联工单</label><el-input :model-value="patSel?.sourceBillNo || ''" readonly class="f-readonly" /></div>
                <div class="field"><label>车间</label><el-input :model-value="patSel?.workshop || ''" readonly class="f-readonly" /></div>
                <div class="field"><label>产品名称</label><el-input :model-value="patSel?.productName || ''" readonly class="f-readonly" /></div>
                <div class="field"><label>批次/卷号</label><el-input :model-value="patSel?.batchNo || ''" readonly class="f-readonly" /></div>
                <div class="field"><label>抽检量</label><el-input :model-value="patSel ? num(patSel.sampleQty) : ''" readonly class="f-readonly" /></div>
                <div class="field"><label>合格 / 不良</label>
                  <el-input :model-value="patSel ? num(patSel.qualifiedQty) + ' / ' + num(patSel.unqualifiedQty) : ''" readonly class="f-readonly" />
                </div>
              </div>
              <div class="field" style="margin-top:8px">
                <label>巡检不合格原因 / 处置说明</label>
                <el-input v-model="patReason" placeholder="如: 膜面划伤, 需冻结本批次返工" />
              </div>
            </div>

            <div class="semi-foot">
              <el-button type="primary" :loading="patSubmitting" @click="onSavePat('PASS')" :disabled="!patSel"><el-icon><Check /></el-icon>判定合格 (PASS)</el-button>
              <el-button type="danger" :loading="patSubmitting" @click="onSavePat('FAIL')" :disabled="!patSel"><el-icon><CircleClose /></el-icon>不合格 · 冻结批次</el-button>
              <el-button @click="loadPat"><el-icon><Refresh /></el-icon>刷新</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 3. 生产环境检查 (xk_env_check + 检查清单 V1.2 固定模板, 对齐 image #6) -->
      <el-tab-pane label="生产环境检查" name="env">
        <div class="env-layout" v-loading="envLoading">
          <!-- 顶部: 发现安全隐患 banner (有 HIGH/MEDIUM 风险时显示) -->
          <div v-if="envAlertCount > 0" class="env-banner">
            <el-icon class="env-banner-ic"><WarningFilled /></el-icon>
            <div class="env-banner-body">
              <b>发现 {{ envAlertCount }} 项安全隐患</b>
              <small>请针对发现的隐患上传整改前后照片，形成检查闭环</small>
            </div>
            <span class="env-banner-no">{{ envSel?.checkNo || 'AJ-' + todayStr() + '-006' }}</span>
          </div>

          <!-- 单卡: 检查清单 (V1.2 固定模板 5 项) + 卫生问题记录 + 保存动作 -->
          <div class="env-card">
            <!-- 检查项目 head -->
            <div class="env-checklist-head">
              <div>
                <b>XK · 生产环境检查项目</b>
                <small>吹膜法生产、食品塑料袋生产许可可检查表 V1.2</small>
              </div>
              <span class="env-checklist-score">完成 {{ envCheckedCount }}/{{ envItems.length }}</span>
            </div>

            <!-- 5 项逐条 (mock 固定, 第 4 项发现问题 → 触发下方整改表单) -->
            <div class="env-items">
              <div v-for="(it, i) in envItems" :key="i" :class="['env-row', it.status === 'ISSUE' ? 'issue' : 'pass']">
                <span class="env-no">{{ String(i + 1).padStart(2, '0') }}</span>
                <span class="env-text">{{ it.text }}</span>
                <span class="env-verdict">
                  <span class="env-v-label" :class="it.status === 'PASS' ? 'ok' : 'bad'">{{ it.status === 'PASS' ? '符合' : '发现问题' }}</span>
                  <!-- v1.1.108: 检查项右侧改开关 — 开=符合(PASS), 关=发现问题(ISSUE). 任意项关 → 触发下方整改表单; 全开 → 隐藏 -->
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
                  <small>{{ envSel?.checkContent || '洁净区物料下发现外包装破损, 存在异物与虫害风险' }}</small>
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
      </el-tab-pane>

      <!-- 3.5 v1.1.101 XK — 进货查验 (IQC) — 跳到独立台账页 -->
      <el-tab-pane label="进货查验 (IQC)" name="iqc">
        <div class="rec-ledger" style="padding: 16px">
          <el-alert title="进货查验详情请进入独立台账" type="info" show-icon :closable="false" style="margin-bottom: 16px">
            v1.1.101 XK — 原料进货检验 (IQC) 已从 5 类检验单中独立, 详情见独立台账页
          </el-alert>
          <el-button type="primary" @click="$router.push('/xk/iqc-ledger')">
            打开进货查验台账
          </el-button>
        </div>
      </el-tab-pane>

      <!-- 4. 检验记录与报告 (对齐 image #11) -->
      <el-tab-pane label="检验记录与报告" name="report">
        <div class="rec-ledger">
          <!-- 台账头卡 -->
          <div class="rec-ledger-head">
            <div>
              <b>检验记录与报告台账</b>
              <small>原料/半成品/巡检/成品/退货 + 环境 共 6 级检验记录及签发报告统一归档 (v1.1.97+ 真后端, v1.1.98 合并生产环境检查)</small>
            </div>
            <div class="rec-ledger-tools">
              <el-select v-model="stageFilter" size="default" style="width:160px" @change="loadReport">
                <el-option v-for="o in STAGE_OPTIONS" :label="o.label" :value="o.value" />
              </el-select>
              <div class="rec-ledger-search">
                <el-icon><Search /></el-icon>
                <el-input v-model="recQuery" placeholder="搜索批次、产品或记录编号" clearable />
              </div>
            </div>
          </div>

          <!-- 台账表 -->
          <div class="rec-table">
            <div class="rec-table-head">
              <div class="tc c-no">记录编号</div>
              <div class="tc c-type">检验类型</div>
              <div class="tc c-obj">产品 / 批次</div>
              <div class="tc c-time">检验时间</div>
              <div class="tc c-who">检验员</div>
              <div class="tc c-result">结论</div>
              <div class="tc c-report">报告</div>
            </div>
            <div class="rec-table-body" v-loading="recLoading">
              <div v-for="r in filteredReportRows" :key="r.id" class="rec-table-row">
                <div class="tc c-no"><span class="mono">{{ r.billNo }}</span></div>
                <div class="tc c-type"><el-tag :type="stageTag(r.inspectionType)" size="small" effect="light" round>{{ stageLabel(r.inspectionType) }}</el-tag></div>
                <div class="tc c-obj">{{ r.productName || '-' }} / {{ r.batchNo || '-' }}</div>
                <div class="tc c-time mono">{{ r.billDate || '-' }}</div>
                <div class="tc c-who">{{ r.inspectorName || '-' }}</div>
                <div class="tc c-result"><el-tag :type="r.result === 'PASS' || r.result === 'PASS_DISPOSITION' ? 'success' : 'danger'" size="small" effect="light">{{ r.result || '-' }}</el-tag></div>
                <div class="tc c-report"><span class="rec-link" @click="onOpenReport(r)"><el-icon><Document /></el-icon>查看</span></div>
              </div>
              <div v-if="!recLoading && !filteredReportRows.length" class="rec-table-empty">暂无匹配的检验记录</div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 5. 检验设备校准 (对齐 image #12-#13) -->
      <el-tab-pane label="检验设备校准" name="cal">
        <!-- 4 统计卡 (真后端) -->
        <div class="cal-stats">
          <div class="cal-stat s-blue"><small>检验设备</small><div class="cal-num"><b>{{ calStat.total }}</b><span>台</span></div></div>
          <div class="cal-stat s-green"><small>校准有效</small><div class="cal-num"><b>{{ calStat.valid }}</b><span>台</span></div></div>
          <div class="cal-stat s-orange"><small>30 天内到期</small><div class="cal-num"><b>{{ calStat.expiring }}</b><span>台</span></div></div>
          <div class="cal-stat s-red"><small>已到期</small><div class="cal-num"><b>{{ calStat.expired }}</b><span>台</span></div></div>
        </div>

        <!-- 待处理警告条 (动态: 30天到期 + 已到期合计) -->
        <div v-if="calStat.expiring + calStat.expired > 0" class="cal-alert">
          <el-icon><WarningFilled /></el-icon>
          <div class="cal-alert-text">
            <b>{{ calStat.expiring + calStat.expired }} 台设备需要处理</b>
            <small>{{ calStat.expiring }} 台 30 天内到期, {{ calStat.expired }} 台已到期.</small>
          </div>
          <el-button @click="onGenerateCal">生成校准计划</el-button>
        </div>

        <div class="cal-layout">
          <!-- 左: 设备台账 (真后端) -->
          <div class="cal-ledger">
            <div class="cal-ledger-head">
              <div><b>检验设备台账</b><small>鉴定、校准和报告统一归档 (v1.1.97 真后端)</small></div>
              <div style="display:flex; gap:8px; align-items:center;">
                <div class="cal-ledger-search">
                  <el-icon><Search /></el-icon>
                  <el-input v-model="calQuery" placeholder="搜索设备名称或编号" clearable />
                </div>
                <el-button type="primary" size="default" @click="onNewCalEq"><el-icon><Plus /></el-icon>新增校准设备</el-button>
              </div>
            </div>
            <div class="cal-table-head">
              <div class="cc-col c-dev">设备</div>
              <div class="cc-col c-spec">量程 / 精度</div>
              <div class="cc-col c-loc">位置</div>
              <div class="cc-col c-last">最近校准</div>
              <div class="cc-col c-next">下次校准</div>
              <div class="cc-col c-status">状态</div>
            </div>
            <div class="cal-table-body" v-loading="calLoading">
              <div
                v-for="d in filteredCalRows"
                :key="d.id"
                class="cal-table-row"
                :class="{ active: d.id === calSel?.id }"
                @click="onSelectCal(d)"
              >
                <div class="cc-col c-dev"><el-icon><Tools /></el-icon><span class="dev-name">{{ d.name }}<small class="dev-code mono">{{ d.code }}</small></span></div>
                <div class="cc-col c-spec">{{ d.measureRange || d.spec || '-' }}</div>
                <div class="cc-col c-loc">{{ d.location || '-' }}</div>
                <div class="cc-col c-last mono">{{ d.lastCalDate || '-' }}</div>
                <div class="cc-col c-next mono">{{ d.nextCalDate || '-' }}</div>
                <div class="cc-col c-status"><el-tag :type="calStatusTag(d.status)" size="small" effect="light">{{ calStatusLabel(d.status) }}</el-tag></div>
              </div>
              <div v-if="!calLoading && !filteredCalRows.length" class="cal-table-empty">暂无设备, 点「新增校准设备」建档</div>
            </div>
          </div>

          <!-- 右: 设备档案 + 校准记录 -->
          <div class="cal-profile">
            <div class="cal-profile-head">
              <div><small>检验设备档案</small><b>{{ calSel?.name || '未选择' }}</b><span class="dev-code mono">{{ calSel?.code }} · {{ calSel?.location || '-' }}</span></div>
              <el-tag :type="calSel ? calStatusTag(calSel.status) : 'info'" size="small" effect="light">{{ calSel ? calStatusLabel(calSel.status) : '未选择' }}</el-tag>
            </div>
            <div class="cal-profile-grid">
              <div class="cal-cell"><small>量程与精度</small><span>{{ calSel?.measureRange || calSel?.spec || '-' }}</span></div>
              <div class="cal-cell"><small>校准周期</small><span>{{ calSel ? (calSel.intvMonths + ' 个月') : '-' }}</span></div>
              <div class="cal-cell"><small>最近校准日期</small><span class="mono">{{ calSel?.lastCalDate || '-' }}</span></div>
              <div class="cal-cell"><small>下次校准日期</small><span class="mono">{{ calSel?.nextCalDate || '-' }}</span></div>
              <div class="cal-cell"><small>校准机构</small><span>{{ calSel?.lastOrg || '-' }}</span></div>
              <div class="cal-cell"><small>证书编号</small><span class="mono">{{ calSel?.lastCertNo || '-' }}</span></div>
            </div>

            <div class="cal-report">
              <div class="cal-report-head">
                <div><b>校准记录</b><small>共 {{ calRecords.length }} 条</small></div>
                <el-button size="small" type="primary" @click="onNewCalRec"><el-icon><Plus /></el-icon>新增校准</el-button>
              </div>
              <div class="cal-report-item" v-for="rec in calRecords" :key="rec.id" style="display:flex; justify-content:space-between; align-items:center;">
                <div class="cal-report-file">
                  <el-icon><Document /></el-icon>
                  <div>
                    <b>{{ rec.certNo || '无证书号' }}</b>
                    <small>校准编号: {{ rec.recordNo }} · {{ rec.calDate }} · {{ rec.result }}</small>
                  </div>
                </div>
                <div>
                  <el-button v-if="rec.result === 'PENDING'" size="small" plain @click="onCheckRec(rec)">审核</el-button>
                  <el-button size="small" plain @click="onViewCal(rec)">查看</el-button>
                </div>
              </div>
              <div v-if="!calRecords.length" class="cal-table-empty" style="padding:12px;">该设备暂无校准记录</div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- =============== 弹窗 1: 新建环境检查 (Tab 3) =============== -->
    <el-dialog v-model="envDialogVisible" title="新建环境检查" width="560px" :close-on-click-modal="false">
      <el-form :model="envForm" :rules="envFormRules" ref="envFormRef" label-width="100">
        <el-form-item label="车间" prop="workshop">
          <el-select v-model="envForm.workshop" filterable allow-create style="width: 100%">
            <el-option label="一车间" value="一车间" />
            <el-option label="二车间" value="二车间" />
            <el-option label="仓库" value="仓库" />
            <el-option label="洁净区" value="洁净区" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查类型" prop="envType">
          <el-select v-model="envForm.envType" style="width: 100%">
            <el-option label="温湿度" value="TEMP_HUMIDITY" />
            <el-option label="洁净度" value="CLEANLINESS" />
            <el-option label="消防安全" value="FIRE_SAFETY" />
            <el-option label="应急照明" value="EMERGENCY_LIGHT" />
            <el-option label="安全门" value="SAFETY_DOOR" />
            <el-option label="供电系统" value="POWER" />
            <el-option label="压缩空气" value="COMPRESSED_AIR" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查主题" prop="checkTitle">
          <el-input v-model="envForm.checkTitle" placeholder="如: 一车间吹膜洁净区 6 月卫生核查" />
        </el-form-item>
        <el-form-item label="检查内容">
          <el-input v-model="envForm.checkContent" type="textarea" :rows="3" placeholder="具体检查项 + 关注点" />
        </el-form-item>
        <el-form-item label="计划时间">
          <el-date-picker v-model="envForm.plannedAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="envDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onEnvSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- =============== 弹窗 2: 提交整改 (Tab 3) =============== -->
    <el-dialog v-model="rectifyDialogVisible" title="提交整改" width="560px" :close-on-click-modal="false">
      <el-form :model="rectifyForm" label-width="100">
        <el-form-item label="整改等级">
          <el-radio-group v-model="rectifyForm.level">
            <el-radio value="LOW">低 (无整改)</el-radio>
            <el-radio value="MEDIUM">中 (限期整改)</el-radio>
            <el-radio value="HIGH">高 (立即整改)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="整改负责人" required>
          <el-input v-model="rectifyForm.owner" placeholder="如: 赵海峰" />
        </el-form-item>
        <el-form-item label="整改期限">
          <el-date-picker v-model="rectifyForm.deadline" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="整改说明">
          <el-input v-model="rectifyForm.rectifyDesc" type="textarea" :rows="3" placeholder="整改措施与结果" />
        </el-form-item>
        <el-form-item label="整改前照片 (JSON)">
          <el-input v-model="rectifyForm.beforePhotos" placeholder='["url1","url2"]' />
        </el-form-item>
        <el-form-item label="整改后照片 (JSON)">
          <el-input v-model="rectifyForm.afterPhotos" placeholder='["url1","url2"]' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rectifyDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onRectifySubmit">提交整改</el-button>
      </template>
    </el-dialog>

    <!-- =============== 弹窗 3: 新建校准设备 (Tab 5) =============== -->
    <el-dialog v-model="newEqDialogVisible" title="新增校准设备" width="560px">
      <el-form :model="newEqForm" label-width="100">
        <el-form-item label="设备名称" required>
          <el-input v-model="newEqForm.name" placeholder="如: 数显测厚仪" />
        </el-form-item>
        <el-form-item label="规格型号">
          <el-input v-model="newEqForm.spec" placeholder="如: 0-10 mm / 0.001 mm" />
        </el-form-item>
        <el-form-item label="厂家">
          <el-input v-model="newEqForm.manufacturer" />
        </el-form-item>
        <el-form-item label="存放位置">
          <el-input v-model="newEqForm.location" placeholder="如: 质量检验室" />
        </el-form-item>
        <el-form-item label="所属车间">
          <el-input v-model="newEqForm.workshop" placeholder="如: 一车间" />
        </el-form-item>
        <el-form-item label="量程与精度">
          <el-input v-model="newEqForm.measureRange" />
        </el-form-item>
        <el-form-item label="校准周期 (月)">
          <el-input-number v-model="newEqForm.intvMonths" :min="1" :max="60" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newEqDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onNewCalEqSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- =============== 弹窗 4: 新建校准记录 (Tab 5) =============== -->
    <el-dialog v-model="newRecDialogVisible" title="新增校准记录" width="560px">
      <el-form :model="newRecForm" label-width="100">
        <el-form-item label="设备">
          <el-input :model-value="calSel ? calSel.code + ' · ' + calSel.name : ''" disabled />
        </el-form-item>
        <el-form-item label="校准日期" required>
          <el-date-picker v-model="newRecForm.calDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="newRecForm.validUntil" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="校准机构">
          <el-input v-model="newRecForm.calOrg" placeholder="如: 市计量质量监测研究院" />
        </el-form-item>
        <el-form-item label="证书编号">
          <el-input v-model="newRecForm.certNo" placeholder="如: JL-2026-TH-0186" />
        </el-form-item>
        <el-form-item label="证书附件 URL">
          <div style="display:flex; gap:8px; align-items:center; width:100%">
            <el-input v-model="newRecForm.certFile" placeholder="点击右侧上传证书附件" style="flex:1" />
            <el-button size="small" @click="onUploadCal">上传</el-button>
          </div>
        </el-form-item>
        <el-form-item label="报告 PDF URL">
          <el-input v-model="newRecForm.reportPdf" />
        </el-form-item>
        <el-form-item label="初始结果">
          <el-select v-model="newRecForm.result" style="width: 100%">
            <el-option label="待审核" value="PENDING" />
            <el-option label="合格" value="PASSED" />
            <el-option label="不合格" value="FAILED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newRecDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onNewCalRecSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- =============== 弹窗 5: 校准审核 (Tab 5) =============== -->
    <el-dialog v-model="checkRecDialogVisible" title="校准审核" width="480px">
      <el-form :model="checkRecForm" label-width="100">
        <el-form-item label="审核结果">
          <el-radio-group v-model="checkRecForm.result">
            <el-radio value="PASSED">合格 (推设备 next_cal_date)</el-radio>
            <el-radio value="FAILED">不合格 (设备停用 / 返厂)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="偏差说明">
          <el-input v-model="checkRecForm.deviation" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="审核人">
          <el-input v-model="checkRecForm.reviewer" placeholder="留空 = 当前登录用户" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="checkRecDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onCheckRecSubmit">提交审核</el-button>
      </template>
    </el-dialog>

    <!-- =============== 弹窗 6: 新建检验单 (Tab 1/2 共用) =============== -->
    <el-dialog v-model="newInspDialogVisible" title="新建检验单" width="560px" :close-on-click-modal="false">
      <el-form :model="newInspForm" ref="newInspFormRef" label-width="100">
        <el-form-item label="检验类型">
          <el-input :model-value="newInspForm.inspectionType === 'FAI' ? '半成品 (FAI)' : '成品 (OQC)'" disabled />
        </el-form-item>
        <el-form-item label="批号" required>
          <el-input v-model="newInspForm.batchNo" placeholder="如: BPC20261012-001" />
        </el-form-item>
        <el-form-item label="产品名称">
          <el-input v-model="newInspForm.productName" placeholder="如: PE 食品包装膜" />
        </el-form-item>
        <el-form-item label="抽样数量">
          <el-input-number v-model="newInspForm.sampleQty" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="合格数量">
          <el-input-number v-model="newInspForm.qualifiedQty" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="newInspForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newInspDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onNewInspSubmit">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- =============== 弹窗 7: 检验单详情 (Tab 1/2/4 共用) / v1.1.98 加 env 分支 =============== -->
    <el-dialog v-model="recDetailVisible" :title="recDetailIsEnv ? '环境检查单详情' : '检验单详情'" width="680px">
      <!-- qms_inspection 分支 (原 Tab 1/2/4 行为) -->
      <template v-if="!recDetailIsEnv && recDetail?.head">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ recDetail.head.billNo }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ recDetail.head.inspectionType }}</el-descriptions-item>
          <el-descriptions-item label="批号">{{ recDetail.head.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ recDetail.head.productName }}</el-descriptions-item>
          <el-descriptions-item label="抽样">{{ recDetail.head.sampleQty }}</el-descriptions-item>
          <el-descriptions-item label="合格">{{ recDetail.head.qualifiedQty }}</el-descriptions-item>
          <el-descriptions-item label="结果" :span="2">
            <el-tag :type="recDetail.head.result === 'PASS' || recDetail.head.result === 'PASS_DISPOSITION' ? 'success' : 'danger'">
              {{ recDetail.head.result }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处置">{{ recDetail.head.disposition || '-' }}</el-descriptions-item>
          <el-descriptions-item label="检验人">{{ recDetail.head.inspectorName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ recDetail.head.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="recDetail?.defects?.length" :data="recDetail.defects" size="small" style="margin-top: 12px">
          <el-table-column prop="defectType" label="缺陷类型" width="120" />
          <el-table-column prop="severity" label="严重度" width="100" />
          <el-table-column prop="qty" label="数量" width="80" />
          <el-table-column prop="disposal" label="处置" />
        </el-table>
      </template>

      <!-- xk_env_check 分支 (v1.1.98 合并生产环境检查) -->
      <template v-else-if="recDetailIsEnv && recDetail?.head">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="检查单号">{{ recDetail.head.checkNo }}</el-descriptions-item>
          <el-descriptions-item label="检查标题">{{ recDetail.head.checkTitle || recDetail.head.envType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="车间">{{ recDetail.head.workshop || '-' }}</el-descriptions-item>
          <el-descriptions-item label="环境类型">{{ recDetail.head.envType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="计划时间">{{ recDetail.head.plannedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="检查人">{{ recDetail.head.inspector || '-' }}</el-descriptions-item>
          <el-descriptions-item label="严重级别">
            <el-tag :type="recDetail.head.level === 'HIGH' ? 'danger' : recDetail.head.level === 'MEDIUM' ? 'warning' : 'info'">
              {{ recDetail.head.level || '-' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ recDetail.head.status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="整改负责人">{{ recDetail.head.rectifyOwner || '-' }}</el-descriptions-item>
          <el-descriptions-item label="整改期限">{{ recDetail.head.rectifyDeadline || '-' }}</el-descriptions-item>
          <el-descriptions-item label="整改状态" :span="2">
            <el-tag :type="recDetail.head.rectifyStatus === 'DONE' ? 'success' : recDetail.head.rectifyStatus === 'IN_PROGRESS' ? 'warning' : 'info'">
              {{ recDetail.head.rectifyStatus || '-' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="整改说明" :span="2">{{ recDetail.head.rectifyDesc || '-' }}</el-descriptions-item>
          <el-descriptions-item label="检查内容" :span="2">{{ recDetail.head.checkContent || '-' }}</el-descriptions-item>
        </el-descriptions>
        <!-- 照片缩略图 (整改前/后) -->
        <div v-if="parsePhotos(recDetail.head.beforePhotos).length || parsePhotos(recDetail.head.afterPhotos).length" style="margin-top: 12px">
          <div v-if="parsePhotos(recDetail.head.beforePhotos).length" style="margin-bottom: 10px">
            <small style="color: #909399">整改前照片：</small>
            <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 4px">
              <el-image
                v-for="(url, i) in parsePhotos(recDetail.head.beforePhotos)"
                :key="'b' + i"
                :src="url"
                :preview-src-list="parsePhotos(recDetail.head.beforePhotos)"
                style="width: 80px; height: 80px; border-radius: 4px"
                fit="cover" />
            </div>
          </div>
          <div v-if="parsePhotos(recDetail.head.afterPhotos).length">
            <small style="color: #909399">整改后照片：</small>
            <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 4px">
              <el-image
                v-for="(url, i) in parsePhotos(recDetail.head.afterPhotos)"
                :key="'a' + i"
                :src="url"
                :preview-src-list="parsePhotos(recDetail.head.afterPhotos)"
                style="width: 80px; height: 80px; border-radius: 4px"
                fit="cover" />
            </div>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import {
  Download, Plus, Camera, Lock, Check, Document, Search, Clock,
  CircleCheckFilled, CircleCheck, WarningFilled, Tools,
  CircleClose, Refresh
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi } from '@/api/quality'
import { envCheckApi } from '@/api/xk'
import { calibrationEquipmentApi, calibrationRecordApi } from '@/api/qa'
import { uploadFile } from '@/api/workflow'

// ===== 通用 =====
const tab = ref('semi')
const inspector = ref('李敏')

// ============== Tab 1: PE膜半成品检验 (FAI) ==============
const semiList = ref([])
const semiSel = ref(null)
const semiItems = ref([])  // 从 head.items JSON 解析
const semiPhotos = ref([])

async function loadSemi() {
  const r = await inspectionApi.page({ pageNum: 1, pageSize: 20, inspectionType: 'FAI' })
  if (r.code === 200) {
    semiList.value = r.data?.records || []
    if (!semiSel.value && semiList.value.length) {
      await onSelectSemi(semiList.value[0])
    }
  }
}
async function onSelectSemi(row) {
  semiSel.value = row
  const r = await inspectionApi.detail(row.id)
  if (r.code === 200 && r.data) {
    const head = r.data.head || r.data
    try { semiItems.value = typeof head.items === 'string' ? JSON.parse(head.items) : (head.items || []) }
    catch (e) { semiItems.value = [] }
    try { semiPhotos.value = typeof head.photoUrls === 'string' ? JSON.parse(head.photoUrls) : (head.photoUrls || []) }
    catch (e) { semiPhotos.value = [] }
  }
}
const semiSubmitting = ref(false)
async function onSaveSemi() {
  if (!semiSel.value) { ElMessage.warning('请先选择一条记录'); return }
  semiSubmitting.value = true
  try {
    const r = await inspectionApi.check(semiSel.value.id, {
      result: 'PASS', disposition: 'NONE', reason: ''
    })
    if (r.code === 200) { ElMessage.success('已保存 PASS'); await loadSemi() }
    else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
  finally { semiSubmitting.value = false }
}

// ============== Tab 2: PE袋成品检验 (OQC) ==============
const finList = ref([])
const finSel = ref(null)
const finItems = ref([])
const finPhotos = ref([])

async function loadFin() {
  const r = await inspectionApi.page({ pageNum: 1, pageSize: 20, inspectionType: 'OQC' })
  if (r.code === 200) {
    finList.value = r.data?.records || []
    if (!finSel.value && finList.value.length) await onSelectFin(finList.value[0])
  }
}
async function onSelectFin(row) {
  finSel.value = row
  const r = await inspectionApi.detail(row.id)
  if (r.code === 200 && r.data) {
    const head = r.data.head || r.data
    try { finItems.value = typeof head.items === 'string' ? JSON.parse(head.items) : (head.items || []) }
    catch (e) { finItems.value = [] }
    try { finPhotos.value = typeof head.photoUrls === 'string' ? JSON.parse(head.photoUrls) : (head.photoUrls || []) }
    catch (e) { finPhotos.value = [] }
  }
}
const finSubmitting = ref(false)
async function onSaveFin() {
  if (!finSel.value) { ElMessage.warning('请先选择一条记录'); return }
  finSubmitting.value = true
  try {
    const r = await inspectionApi.check(finSel.value.id, {
      result: 'PASS', disposition: 'NONE', reason: ''
    })
    if (r.code === 200) { ElMessage.success('已保存 PASS'); await loadFin() }
    else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
  finally { finSubmitting.value = false }
}

// ============== Tab 2.5: 过程巡检 (PAT) — 工序报工自动生成的巡检草稿 ==============
const patList = ref([])
const patSel = ref(null)
const patReason = ref('')
const patSubmitting = ref(false)
// 数值归一 (PAT 各量字段可能为字符串/null)
function num(v) { const n = Number(v || 0); return isFinite(n) ? n : 0 }
function patResultLabel(r) {
  return { PASS: '合格', PASS_DISPOSITION: '合格(处置)', FAIL: '不合格', FAIL_REJECT: '拒收', HOLD: '待判定' }[r] || (r || '待判定')
}
async function loadPat() {
  const r = await inspectionApi.page({ pageNum: 1, pageSize: 50, inspectionType: 'PAT' })
  if (r.code === 200) {
    patList.value = r.data?.records || []
    if (!patSel.value && patList.value.length) onSelectPat(patList.value[0])
  }
}
async function onSelectPat(row) {
  patSel.value = row
  patReason.value = ''
}
async function onSavePat(result) {
  if (!patSel.value) { ElMessage.warning('请先选择一条巡检记录'); return }
  patSubmitting.value = true
  try {
    const disposition = result === 'FAIL' ? 'FROZEN' : 'NONE'
    const r = await inspectionApi.check(patSel.value.id, {
      result, disposition, reason: patReason.value || ''
    })
    if (r.code === 200) {
      ElMessage.success(result === 'PASS' ? '已判定合格' : '已判定不合格并冻结批次')
      await loadPat()
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
  finally { patSubmitting.value = false }
}

// ============== Tab 3: 生产环境检查 (xk_env_check, V1.2 固定 5 项模板) ==============
const envLoading = ref(false)
const envSel = ref(null)         // 当前正在编辑的检查单 (DRAFT) 或 null (新建未保存)
const envSaving = ref(false)

// 5 项检查项目 (V1.2 固定模板, mock 数据; 第 4 项默认"发现问题"触发整改表单)
// 实际生产可替换为从后端 xk_env_check_template 拉取
const envItems = ref([
  { text: '人员工作服、发帽及手部清洁符合要求', status: 'PASS' },
  { text: '原料与半成品无破损分装发放', status: 'PASS' },
  { text: '设备转轮清洗、无油无异味', status: 'PASS' },
  { text: '现场无生产产品或异物', status: 'ISSUE' },
  { text: '蚊、蚂蚁及鼠虫防治运行正常', status: 'PASS' }
])
const hasIssue = computed(() => envItems.value.some(i => i.status === 'ISSUE'))
const envCheckedCount = computed(() => envItems.value.filter(i => i.status === 'PASS').length)
// v1.1.108: 隐患数 = 发现问题的检查项数 (多项都开"问题"时累加, 不再写死 1)
const envAlertCount = computed(() => envItems.value.filter(i => i.status === 'ISSUE').length)

// v1.1.108: 检查项开关切换 — 关掉某项(设为"发现问题")自动弹整改表单; 全符合时由 hasIssue 收敛表单
function onEnvItemToggle(it) {
  // 开关 v-model 已改 it.status, 这里只是切到"发现问题"时提示用户填整改记录
  if (it.status === 'ISSUE') {
    ElMessage.warning('第 ' + (envItems.value.indexOf(it) + 1) + ' 项发现问题, 请在下方填写卫生问题整改记录')
  }
}

function todayStr() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
}

// 整改表单 (与 image #6 对齐, 数据走 xk_env_check 整改字段)
const rectifyForm = ref({
  level: 'LOW', owner: '', deadline: '', rectifyDesc: '',
  rectifyStatus: 'IN_PROGRESS',
  beforePhotos: [], afterPhotos: []
})

// 照片上传 → 真 /system/upload/file (走共享 axios 实例, 返回 URL 回填照片数组)
function pickFile(cb) {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.accept = 'image/*'
  inp.onchange = async () => {
    const f = inp.files && inp.files[0]
    if (!f) return
    try {
      const url = await uploadFile(f)
      cb(url, f.name)
      ElMessage.success('照片已上传')
    } catch (e) { ElMessage.error((e && e.message) || '上传失败') }
  }
  inp.click()
}
function onUploadPhoto(side) {
  const arr = side === 'before' ? rectBeforeArr : rectAfterArr
  if (arr.value.length >= 6) { ElMessage.warning('最多 6 张照片'); return }
  pickFile((url, name) => arr.value.push({ id: 'up' + Date.now(), url, name }))
}

// 保存整改记录 → POST /xk/env-check/save (新建 DRAFT) 或 /xk/env-check/{id}/check (提交整改)
async function onSaveEnvRecord() {
  if (!rectifyForm.value.owner) { ElMessage.warning('请填写整改负责人'); return }
  if (!rectifyForm.value.deadline) { ElMessage.warning('请选择整改期限'); return }
  envSaving.value = true
  try {
    const payload = {
      ...rectifyForm.value,
      beforePhotos: JSON.stringify(rectifyForm.value.beforePhotos || []),
      afterPhotos: JSON.stringify(rectifyForm.value.afterPhotos || []),
      workshop: '一车间',
      envType: 'CLEANLINESS',
      checkTitle: '生产环境检查 · V1.2',
      checkContent: '洁净区物料下发现外包装破损, 存在异物与虫害风险',
      plannedAt: new Date().toISOString().slice(0, 16).replace('T', ' ')
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

// v1.1.108: 全合格场景保存检查记录.
// 5 项全 PASS (hasIssue=false) 时也要能生成并归档一条 xk_env_check 记录,
// 而不是只有"发现问题"才落库. 走 save(建 DRAFT) → check(level=null, rectifyStatus=DONE):
//   level 留空 = 全合格, 后端 X6 门禁只对 MEDIUM/HIGH 强制照片, 全合格 0 照片合法.
// 检查项快照 (5 项文本 + 全部"符合") 写进 checkContent, 台账里那条记录能看到本次查了什么.
async function onSaveEnvPass() {
  const passItems = envItems.value.filter(i => i.status === 'PASS')
  const snapshot = (passItems.length
    ? passItems.map((i, n) => (n + 1) + '. ' + i.text + ' — 符合').join('\n')
    : '生产环境检查 (V1.2)')
  envSaving.value = true
  try {
    const payload = {
      level: null,                          // 全合格, 无问题等级
      rectifyOwner: '', deadline: '', rectifyDesc: '',
      rectifyStatus: 'DONE',
      beforePhotos: '', afterPhotos: '',
      workshop: '一车间',
      envType: 'CLEANLINESS',
      checkTitle: '生产环境检查 · V1.2 (全部合格)',
      checkContent: '5 项检查全部符合, 无需整改. 检查项:\n' + snapshot,
      plannedAt: new Date().toISOString().slice(0, 16).replace('T', ' ')
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
  } finally { envLoading.value = false }
}

// ============== Tab 4: 检验记录与报告 (v1.1.98 合并 5 类 qms_inspection + 1 类 xk_env_check) ==============
const recQuery = ref('')
const stageFilter = ref('all')
const reportList = ref([])
const recLoading = ref(false)
const recDetailIsEnv = ref(false)  // v1.1.98 弹窗分支路由: env 行 → 调 envCheckApi.detail
const STAGE_OPTIONS = [
  { value: 'all',       label: '全部' },
  { value: 'IQC',       label: '原料 IQC' },
  { value: 'FAI',       label: '半成品 FAI' },
  { value: 'PAT',       label: '巡检 PAT' },
  { value: 'OQC',       label: '成品 OQC' },
  { value: 'OQC_RETURN',label: '退货 OQC_RETURN' },
  { value: 'ENV',       label: '环境 ENV' }
]
// v1.1.98 R17 异构数据源前端归一化: 把 qms_inspection 行 + xk_env_check 行 映射成统一台账行对象
function normalizeRow(rec, type) {
  if (type === 'inspection') {
    return {
      billNo:         rec.billNo || '',
      inspectionType: rec.inspectionType || '',
      productName:    rec.productName || '',
      batchNo:        rec.batchNo || '',
      billDate:       rec.billDate || '',
      inspectorName:  rec.inspectorName || rec.inspector || '',
      result:         rec.result || '',
      sampleQty:      rec.sampleQty,
      qualifiedQty:   rec.qualifiedQty,
      disposition:    rec.disposition || '',
      checkTime:      rec.checkTime || rec.createTime || '',
      id:             rec.id,
      _source:        'inspection',
      createTime:     rec.createTime || ''
    }
  }
  // env (xk_env_check)
  return {
    billNo:         rec.checkNo || '',
    inspectionType: 'ENV',
    productName:    (rec.workshop || '生产环境') + ' · ' + (rec.checkTitle || rec.envType || '环境检查'),
    batchNo:        '—',
    billDate:       rec.plannedAt || rec.createTime || '',
    inspectorName:  rec.inspector || '',
    result:         rec.rectifyStatus === 'DONE' ? 'PASS'
                  : rec.rectifyStatus === 'IN_PROGRESS' ? 'FAIL'
                  : rec.status === 'DRAFT' ? 'HOLD' : '',
    sampleQty:      null,
    qualifiedQty:   null,
    disposition:    rec.rectifyStatus === 'DONE' ? 'PASS' : '',
    checkTime:      rec.plannedAt || rec.createTime || '',
    id:             rec.id,
    _source:        'env',
    createTime:     rec.createTime || ''
  }
}
// v1.1.98 R19 兜底 beforePhotos/afterPhotos 多种形态: JSON 字符串 / 数组 / null
function parsePhotos(field) {
  if (!field) return []
  if (Array.isArray(field)) return field
  try { const p = JSON.parse(field); return Array.isArray(p) ? p : [] } catch (e) { return [] }
}
async function loadReport() {
  recLoading.value = true
  try {
    // stageFilter 路由: 'all' → 两边都拉; qms 5 类 → 只 inspection; 'ENV' → 只 env
    const inspectionP = stageFilter.value === 'ENV'
      ? Promise.resolve({ code: 200, data: { records: [] } })
      : (() => {
          const p = { pageNum: 1, pageSize: 50 }
          if (stageFilter.value !== 'all') p.inspectionType = stageFilter.value
          return inspectionApi.page(p)
        })()
    const envP = (stageFilter.value === 'all' || stageFilter.value === 'ENV')
      ? envCheckApi.page({ pageNum: 1, pageSize: 50 })
      : Promise.resolve({ code: 200, data: { records: [] } })
    // v1.1.98 R20 用 allSettled 避免 env 500 拖累 qms
    const [r1, r2] = await Promise.allSettled([inspectionP, envP])
    const insList = (r1.status === 'fulfilled' && r1.value.code === 200 ? r1.value.data?.records || [] : []).map(r => normalizeRow(r, 'inspection'))
    const envList = (r2.status === 'fulfilled' && r2.value.code === 200 ? r2.value.data?.records || [] : []).map(r => normalizeRow(r, 'env'))
    // 倒序按 billDate (env 是 plannedAt) + createTime 兜底
    reportList.value = [...insList, ...envList].sort((a, b) => {
      const ta = a.billDate ? new Date(a.billDate).getTime() : 0
      const tb = b.billDate ? new Date(b.billDate).getTime() : 0
      if (tb !== ta) return tb - ta
      return (b.createTime || '').localeCompare(a.createTime || '')
    })
  } finally { recLoading.value = false }
}
const filteredReportRows = computed(() => {
  let list = reportList.value
  const q = recQuery.value.trim().toLowerCase()
  if (q) list = list.filter(r =>
    (r.billNo || '').toLowerCase().includes(q) ||
    (r.productName || '').toLowerCase().includes(q) ||
    (r.batchNo || '').toLowerCase().includes(q)
  )
  return list
})
function stageLabel(type) {
  const m = { IQC: '原料', FAI: '半成品', PAT: '巡检', OQC: '成品', OQC_RETURN: '退货', ENV: '环境' }
  return m[type] || type
}
function stageTag(type) {
  return ({ IQC: 'warning', FAI: 'primary', PAT: 'success', OQC: 'info', OQC_RETURN: 'danger', ENV: 'warning' })[type] || 'info'
}
const recDetailVisible = ref(false)
const recDetail = ref(null)
async function onOpenReport(row) {
  recDetailIsEnv.value = row && row._source === 'env'
  try {
    const r = row && row._source === 'env'
      ? await envCheckApi.detail(row.id)
      : await inspectionApi.detail(row.id)
    if (r.code === 200) { recDetail.value = { head: r.data }; recDetailVisible.value = true }
    else ElMessage.error(r.msg || '加载失败')
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载失败')
  }
}

// ============== Tab 5: 检验设备校准 ==============
const calList = ref([])
const calSel = ref(null)
const calQuery = ref('')
const calLoading = ref(false)
const calRecords = ref([])  // 当前选中设备的校准记录列表

async function loadCal() {
  calLoading.value = true
  try {
    const r = await calibrationEquipmentApi.listAll()
    if (r.code === 200) {
      calList.value = r.data || []
      if (!calSel.value && calList.value.length) await onSelectCal(calList.value[0])
    }
  } finally { calLoading.value = false }
}
async function onSelectCal(row) {
  calSel.value = row
  const r = await calibrationRecordApi.page({ equipmentId: row.id, pageNum: 1, pageSize: 20 })
  if (r.code === 200) calRecords.value = r.data?.records || []
}
const filteredCalRows = computed(() => {
  const q = calQuery.value.trim()
  if (!q) return calList.value
  return calList.value.filter(d =>
    (d.name || '').includes(q) || (d.code || '').includes(q) || (d.location || '').includes(q)
  )
})
function calStatusTag(status) {
  if (status === 'VALID') return 'success'
  if (status === 'EXPIRING_SOON') return 'warning'
  if (status === 'SCRAP') return 'info'
  return 'danger'  // EXPIRED
}
function calStatusLabel(status) {
  if (status === 'VALID') return '有效'
  if (status === 'EXPIRING_SOON') return '即将到期'
  if (status === 'SCRAP') return '报废'
  return '已到期'
}
const calStat = computed(() => {
  const total = calList.value.length
  const valid = calList.value.filter(c => c.status === 'VALID').length
  const expiring = calList.value.filter(c => c.status === 'EXPIRING_SOON').length
  const expired = calList.value.filter(c => c.status === 'EXPIRED').length
  return { total, valid, expiring, expired }
})

// 新增校准设备弹窗
const newEqDialogVisible = ref(false)
const newEqForm = ref({
  name: '', spec: '', manufacturer: '', location: '',
  workshop: '', measureRange: '', intvMonths: 12
})
function onNewCalEq() {
  newEqForm.value = { name: '', spec: '', manufacturer: '', location: '', workshop: '', measureRange: '', intvMonths: 12 }
  newEqDialogVisible.value = true
}
async function onNewCalEqSubmit() {
  if (!newEqForm.value.name) { ElMessage.warning('请填设备名称'); return }
  try {
    const r = await calibrationEquipmentApi.save(newEqForm.value)
    if (r.code === 200) {
      ElMessage.success('已新增校准设备')
      newEqDialogVisible.value = false
      await loadCal()
      if (r.data) await onSelectCal(r.data)
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
}

// 新增校准记录弹窗
const newRecDialogVisible = ref(false)
const newRecForm = ref({
  equipmentId: null,
  calDate: null, validUntil: null,
  calOrg: '', certNo: '', certFile: '', reportPdf: '',
  result: 'PENDING', deviation: '', remark: ''
})
function onNewCalRec() {
  if (!calSel.value) { ElMessage.warning('请先选择一台设备'); return }
  newRecForm.value = {
    equipmentId: calSel.value.id,
    calDate: null, validUntil: null,
    calOrg: '', certNo: '', certFile: '', reportPdf: '',
    result: 'PENDING', deviation: '', remark: ''
  }
  newRecDialogVisible.value = true
}
async function onNewCalRecSubmit() {
  if (!newRecForm.value.calDate) { ElMessage.warning('请选择校准日期'); return }
  try {
    const r = await calibrationRecordApi.save(newRecForm.value)
    if (r.code === 200) {
      ElMessage.success('已新增校准记录, 等待审核')
      newRecDialogVisible.value = false
      await onSelectCal(calSel.value)  // 刷新记录列表
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
}

// 校准审核弹窗
const checkRecDialogVisible = ref(false)
const checkRecForm = ref({ result: 'PASSED', deviation: '', reviewer: '' })
const checkRecTarget = ref(null)
function onCheckRec(row) {
  checkRecTarget.value = row
  checkRecForm.value = { result: 'PASSED', deviation: row.deviation || '', reviewer: '' }
  checkRecDialogVisible.value = true
}
async function onCheckRecSubmit() {
  if (!checkRecTarget.value) return
  try {
    const r = await calibrationRecordApi.check(checkRecTarget.value.id, checkRecForm.value)
    if (r.code === 200) {
      ElMessage.success('已审核, 状态 ' + checkRecForm.value.result)
      checkRecDialogVisible.value = false
      await onSelectCal(calSel.value)  // 刷新记录
      await loadCal()  // 设备状态也刷新 (EXPIRED → VALID)
    } else ElMessage.error(r.msg || '审核失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '审核失败') }
}

function onGenerateCal() { ElMessage.info('生成校准计划: 选中 30 天内到期的设备') }
function onUploadCal() {
  // 校准证书附件 → 真上传 /system/upload/file, 返回 URL 回填新校准记录表单 certFile (保存时随 record.certFile 落库)
  pickFile((url, name) => {
    newRecForm.value.certFile = url
    ElMessage.success('证书附件已上传: ' + name)
  })
}
function onCalHistory() { ElMessage.info(`已显示 ${calRecords.value.length} 条校准历史`) }
function onViewCal(rec) {
  if (rec.reportPdf) window.open(rec.reportPdf, '_blank')
  else if (rec.certFile) window.open(rec.certFile, '_blank')
  else ElMessage.info('该记录未上传证书/报告 PDF')
}

// ============== 顶部全局按钮 ==============
// 导出质量档案 → 客户端 CSV 导出当前检验记录 (v1.1.98 含 qms 5 类 + xk_env_check, 归一化后字段已对齐).
// 后端暂无 /qms/inspection/export 端点, 先用前端导出满足"下载档案"需求, 不阻塞.
async function onExport() {
  try { await loadReport() } catch (e) {}
  const rows = filteredReportRows.value
  if (!rows.length) { ElMessage.warning('暂无可导出的检验记录'); return }
  const head = ['单号', '检验类型', '产品', '批号', '抽样量', '合格量', '结果', '判定', '检验时间']
  const lines = rows.map(r => [
    r.billNo, stageLabel(r.inspectionType), r.productName, r.batchNo,
    r.sampleQty ?? '', r.qualifiedQty ?? '', r.result || '', r.disposition || '',
    (r.checkTime || r.createTime || '').toString().slice(0, 16)
  ].join('\t'))
  const csv = '﻿' + head.join('\t') + '\n' + lines.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '质量检验档案-' + new Date().toISOString().slice(0, 10) + '.csv'
  a.click()
  URL.revokeObjectURL(a.href)
  const envCnt = rows.filter(r => r._source === 'env').length
  ElMessage.success('已导出 ' + rows.length + ' 条检验记录 (含 ' + envCnt + ' 条环境检查)')
}

// 新建检验 (Tab 1/2 共用) - 走 inspectionApi.create
const newInspDialogVisible = ref(false)
const newInspForm = ref({
  inspectionType: 'FAI', batchNo: '', productName: '',
  sampleQty: 1, qualifiedQty: 1, remark: ''
})
const newInspFormRef = ref(null)
function onNew() {
  newInspForm.value = {
    inspectionType: tab.value === 'finished' ? 'OQC' : 'FAI',
    batchNo: '', productName: '', sampleQty: 1, qualifiedQty: 1, remark: ''
  }
  newInspDialogVisible.value = true
}
async function onNewInspSubmit() {
  if (!newInspFormRef.value) return
  try { await newInspFormRef.value.validate() } catch (e) { return }
  if (!newInspForm.value.batchNo) { ElMessage.warning('请填批号'); return }
  try {
    const r = await inspectionApi.create(newInspForm.value)
    if (r.code === 200) {
      ElMessage.success('已创建草稿, 单号 ' + (r.data?.billNo || ''))
      newInspDialogVisible.value = false
      if (tab.value === 'semi') await loadSemi()
      else if (tab.value === 'finished') await loadFin()
      else await loadReport()
    } else ElMessage.error(r.msg || '创建失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '创建失败') }
}

function onAddPhoto(which) {
  // Tab 1/2/3 照片添加 → 真上传 /system/upload/file (替代旧 unsplash 占位)
  let arr
  if (which === 'fin') arr = finPhotos
  else if (which === 'rb' || which === 'ra') arr = which === 'rb' ? rectBeforeArr : rectAfterArr
  else arr = semiPhotos
  if (!arr || arr.value.length >= 6) { ElMessage.warning('最多 6 张照片'); return }
  pickFile((url, name) => arr.value.push({ id: (which || 'p') + Date.now(), url, name }))
}
// 整改照片 (Tab 3 用, 与 fin/semi 分离)
const rectBeforeArr = ref([])
const rectAfterArr = ref([])

// ============== 初始化: 监听 tab 切换加载 ==============
onMounted(() => { loadSemi(); loadFin(); loadPat() })
// tab 切换时按需加载 (避免首屏 5 个接口并发)
let prevTab = tab.value
watch(tab, async (t) => {
  if (t === prevTab) return
  prevTab = t
  if (t === 'semi' && !semiList.value.length) await loadSemi()
  if (t === 'finished' && !finList.value.length) await loadFin()
  if (t === 'env') await loadEnv()
  if (t === 'report' && !reportList.value.length) await loadReport()
  if (t === 'cal' && !calList.value.length) await loadCal()
})
</script>

<style scoped lang="scss">
.xk-safety { display: flex; flex-direction: column; gap: 16px; }

.xk-tabs { margin-top: 4px; }
.xk-tabs :deep(.el-tabs__item) { font-size: 14px; }
.xk-tabs :deep(.el-tabs__active-bar) { height: 3px; border-radius: 3px; }

/* ===== 1. 半成品检验 ===== */
.semi-layout { display: grid; grid-template-columns: 1fr 320px; gap: 18px; align-items: start; }
@media (max-width: 1200px) { .semi-layout { grid-template-columns: 1fr; } }
.semi-main { display: flex; flex-direction: column; gap: 16px; }

.rec-list-card { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 14px 16px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.rec-list-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; }
.rec-list-head b { font-size: 13px; font-weight: 700; color: var(--text); }
.rec-list-head small { font-size: 11px; color: var(--muted); }
.rec-list { display: flex; flex-direction: column; gap: 8px; max-height: 220px; overflow-y: auto; }
.rec-list-item {
  padding: 10px 12px; background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 10px; cursor: pointer; transition: all .15s;
  &:hover { background: #f4f8fd; border-color: #cfe0f2; }
  &.active { border-color: var(--primary-color); background: #f4f8fd; box-shadow: 0 4px 14px rgba(22, 113, 217, .1); }
}
.rli-top { display: flex; align-items: center; justify-content: space-between; }
.rli-top strong { font-size: 13px; font-weight: 700; color: var(--text); }
.rli-sub { font-size: 11px; color: var(--muted); margin-top: 3px; }
.rec-list-empty { font-size: 12px; color: var(--muted); text-align: center; padding: 12px 0; }

.rec-head {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 16px 20px; display: flex; align-items: center; justify-content: space-between;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.rec-head-l .el-tag { margin-bottom: 8px; }
.rec-head-l h2 { font-size: 17px; font-weight: 740; color: var(--text); margin: 0 0 4px; }
.rec-head-l small { font-size: 12px; color: var(--muted); }

.form-card { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 20px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 24px; }
@media (max-width: 900px) { .form-grid { grid-template-columns: 1fr; } }
.field { display: flex; flex-direction: column; gap: 6px; }
.field > label { font-size: 13px; color: var(--text); font-weight: 550; }
.field > label i { color: var(--danger); font-style: normal; margin-left: 2px; }
.field .f-readonly { background: #fbfcfe; }
.field .f-readonly :deep(.el-input__inner) { color: #4b5a6b; }
.field-hint { font-size: 11px; color: var(--muted); }

// 检验项目表
.check-table-card { background: #fff; border: 1px solid var(--line); border-radius: 14px; overflow: hidden; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.ct-head { display: flex; background: #f7f9fc; padding: 12px 18px; font-size: 12px; color: var(--muted); font-weight: 650; border-bottom: 1px solid var(--line); }
.ct-body { display: flex; flex-direction: column; }
.ct-row { display: flex; padding: 13px 18px; border-bottom: 1px solid #eef1f4; font-size: 13px;
  &:last-child { border-bottom: 0; }
  &:hover { background: #fbfcfe; }
}
.ct-col { white-space: nowrap; }
.c-item { flex: 0 0 130px; font-weight: 600; color: var(--text); }
.c-req { flex: 1; color: #5a6b7d; }
.c-method { flex: 0 0 110px; color: #5a6b7d; }
.c-result { flex: 0 0 110px; color: var(--text); font-weight: 550; }
.c-verdict { flex: 0 0 90px; text-align: right; }
@media (max-width: 700px) { .ct-row { flex-wrap: wrap; gap: 6px; } .ct-col { flex: 1 1 40%; } }

// 照片卡
.photo-card { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px 20px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.photo-card-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; }
.photo-card-head b { font-size: 14px; font-weight: 700; color: var(--text); display: block; }
.photo-card-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.photo-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 12px; }
@media (max-width: 900px) { .photo-grid { grid-template-columns: repeat(3, 1fr); } }
.photo-item {
  position: relative; aspect-ratio: 1; border-radius: 10px; overflow: hidden;
  background: #e9eef3; border: 1px solid #dfe6ef;
  img { width: 100%; height: 100%; object-fit: cover; }
  .photo-lock { position: absolute; top: 6px; right: 6px; width: 22px; height: 22px; background: rgba(8, 20, 34, .78); color: #fff; border-radius: 50%; display: grid; place-items: center; font-size: 11px; }
  &.empty {
    border: 1px dashed #aebdcc; background: #f8fafc; color: var(--muted);
    display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; cursor: pointer;
    .camera-circle { color: var(--primary-color); font-size: 18px; }
    strong { font-size: 10px; }
    small { font-size: 9px; color: #9aa7b5; }
    &:hover { border-color: var(--primary-color); color: var(--primary-color); }
  }
}
.binding-note { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #607189; }
.binding-note .el-icon { color: var(--primary-color); }

.semi-foot { display: flex; justify-content: flex-end; gap: 10px; }

// 右侧
.semi-side { display: flex; flex-direction: column; gap: 16px; }
.verdict-card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.verdict-icon {
  width: 44px; height: 44px; border-radius: 50%;
  background: #e9f8ef; color: var(--success); display: grid; place-items: center; margin-bottom: 12px;
}
.verdict-body b { font-size: 15px; font-weight: 740; color: var(--text); display: block; }
.verdict-body p { font-size: 12px; color: var(--muted); margin: 6px 0 14px; line-height: 1.6; }
.verdict-meta { border-top: 1px solid var(--line); padding-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.verdict-meta > div { display: flex; align-items: center; justify-content: space-between; }
.verdict-meta small { font-size: 12px; color: var(--muted); }
.verdict-meta span { font-size: 12px; color: var(--text); font-weight: 600; }

.retain-card { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.retain-card > b { font-size: 14px; font-weight: 720; color: var(--text); display: block; margin-bottom: 14px; }
.retain-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 0; border-top: 1px solid #f0f3f7; font-size: 12px;
  &:first-of-type { border-top: 0; }
}
.retain-row small { color: var(--muted); }
.retain-row span { color: var(--text); font-weight: 600; }

/* ===== 3. 生产环境检查 (对齐 image #8-#10) ===== */
.safety-banner {
  padding: 15px 18px; border: 1px solid #f0cba1; background: #fff8eb; border-radius: 12px;
  display: flex; align-items: center; gap: 13px;
  > span { width: 38px; height: 38px; display: grid; place-items: center; color: var(--warning); background: #fff; border-radius: 9px; flex: 0 0 auto; }
  > div { flex: 1; }
  > div strong { font-size: 13px; color: #846e52; font-weight: 700; }
  > div p { margin: 3px 0 0; color: #846e52; font-size: 11px; }
  > small { color: var(--warning); font-family: ui-monospace, monospace; font-size: 13px; }
}

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

/* ===== 4. 检验记录台账 (对齐 image #11) ===== */
.rec-ledger { display: flex; flex-direction: column; gap: 16px; }
.rec-ledger-head {
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 16px 20px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.rec-ledger-head > div:first-child b { font-size: 15px; font-weight: 730; color: var(--text); display: block; }
.rec-ledger-head > div:first-child small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
.rec-ledger-tools { display: flex; align-items: center; gap: 12px; }
.rec-ledger-search {
  display: flex; align-items: center; gap: 6px; width: 260px;
  background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 0 10px;
  .el-icon { color: #9aa7b5; }
  .el-input { border: 0 !important; }
  .el-input :deep(.el-input__inner) { background: transparent; }
}

.rec-table { background: #fff; border: 1px solid var(--line); border-radius: 14px; overflow: hidden; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.rec-table-head { display: flex; background: #f7f9fc; padding: 12px 20px; font-size: 12px; color: var(--muted); font-weight: 650; }
.rec-table-body { display: flex; flex-direction: column; }
.rec-table-row { display: flex; padding: 15px 20px; border-bottom: 1px solid #eef1f4; font-size: 13px; align-items: center;
  &:last-child { border-bottom: 0; }
  &:hover { background: #fbfcfe; }
}
.rec-table-empty { padding: 30px; text-align: center; color: var(--muted); font-size: 13px; }
.tc { white-space: nowrap; }
.c-no { flex: 0 0 190px; }
.c-type { flex: 0 0 150px; }
.c-obj { flex: 1; }
.c-time { flex: 0 0 110px; }
.c-who { flex: 0 0 90px; }
.c-result { flex: 0 0 90px; }
.c-report { flex: 0 0 150px; }
.rec-link {
  display: inline-flex; align-items: center; gap: 5px;
  color: var(--primary-color); font-size: 12px; font-weight: 600; cursor: pointer;
  &:hover { text-decoration: underline; }
}
.mono { font-family: ui-monospace, 'SF Mono', monospace; font-size: 12px; color: #4b5a6b; }

/* ===== 5. 检验设备校准 (对齐 image #12-#13) ===== */
.cal-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
@media (max-width: 900px) { .cal-stats { grid-template-columns: repeat(2, 1fr); } }
.cal-stat {
  background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 14px 16px;
  position: relative; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
  small { font-size: 12px; color: var(--muted); font-weight: 600; }
  .cal-num { display: flex; align-items: baseline; gap: 5px; margin-top: 8px; }
  .cal-num b { font-size: 30px; font-weight: 760; color: var(--text); line-height: 1; }
  .cal-num span { font-size: 12px; color: var(--muted); }
  &::before { content: ''; position: absolute; left: 0; top: 12px; bottom: 12px; width: 3px; border-radius: 2px; }
  &.s-blue::before { background: var(--primary-color); }
  &.s-green::before { background: var(--success); }
  &.s-orange::before { background: #e8920b; }
  &.s-red::before { background: var(--danger); }
}

.cal-alert {
  display: flex; align-items: center; gap: 12px;
  background: #fff8eb; border: 1px solid #f0cba1; border-radius: 12px; padding: 14px 18px;
  > .el-icon { color: #e8920b; font-size: 22px; flex: 0 0 auto; }
}
.cal-alert-text { flex: 1; }
.cal-alert-text b { font-size: 13px; font-weight: 700; color: #846e52; display: block; }
.cal-alert-text small { font-size: 11px; color: #9a8266; margin-top: 2px; display: block; }

.cal-layout { display: grid; grid-template-columns: 1fr 340px; gap: 16px; align-items: start; }
@media (max-width: 1100px) { .cal-layout { grid-template-columns: 1fr; } }

.cal-ledger { background: #fff; border: 1px solid var(--line); border-radius: 14px; overflow: hidden; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.cal-ledger-head {
  display: flex; align-items: center; justify-content: space-between; gap: 14px;
  padding: 16px 20px; border-bottom: 1px solid var(--line); flex-wrap: wrap;
}
.cal-ledger-head > div:first-child b { font-size: 15px; font-weight: 730; color: var(--text); display: block; }
.cal-ledger-head > div:first-child small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
.cal-ledger-search { display: flex; align-items: center; gap: 6px; width: 240px; background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 0 10px;
  .el-icon { color: #9aa7b5; }
  .el-input :deep(.el-input__inner) { background: transparent; }
}

.cal-table-head { display: flex; background: #f7f9fc; padding: 12px 20px; font-size: 12px; color: var(--muted); font-weight: 650; }
.cal-table-body { display: flex; flex-direction: column; }
.cal-table-row {
  display: flex; align-items: center; padding: 14px 20px; border-bottom: 1px solid #eef1f4; font-size: 13px; cursor: pointer;
  position: relative;
  &:last-child { border-bottom: 0; }
  &:hover { background: #fbfcfe; }
  &.active { background: #f4f9ff; &::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--primary-color); } }
}
.cal-table-empty { padding: 30px; text-align: center; color: var(--muted); font-size: 13px; }
.cc-col { white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.cc-col > .el-icon { color: var(--primary-color); }
.c-dev { flex: 0 0 150px; font-weight: 600; color: var(--text); flex-direction: column; align-items: flex-start; gap: 2px; }
.dev-name { font-size: 13px; font-weight: 600; color: var(--text); }
.dev-code { font-size: 11px; color: #9aa7b5; }
.c-spec { flex: 1; color: #5a6b7d; }
.c-loc { flex: 0 0 100px; color: #5a6b7d; }
.c-last { flex: 0 0 110px; }
.c-next { flex: 0 0 110px; }
.c-status { flex: 0 0 90px; justify-content: flex-end; }
@media (max-width: 900px) { .cal-table-row { flex-wrap: wrap; gap: 8px; } .cc-col { flex: 1 1 40%; } }

// 设备档案
.cal-profile { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.cal-profile-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.cal-profile-head small { font-size: 11px; color: var(--primary-color); font-weight: 600; display: block; }
.cal-profile-head b { font-size: 16px; font-weight: 740; color: var(--text); display: block; margin: 4px 0 2px; }
.cal-profile-head .dev-code { font-size: 11px; color: #9aa7b5; }

.cal-profile-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.cal-cell { background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 8px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.cal-cell small { font-size: 11px; color: var(--muted); }
.cal-cell span { font-size: 13px; color: var(--text); font-weight: 600; }

.cal-report { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 16px; }
.cal-report-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 12px; }
.cal-report-head b { font-size: 13px; font-weight: 720; color: var(--text); display: block; }
.cal-report-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.cal-report-item {
  display: flex; align-items: center; gap: 12px; padding: 12px;
  background: #f4f9ff; border: 1px solid #d6e4f5; border-radius: 10px;
}
.cal-report-file { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.cal-report-file > .el-icon { color: var(--primary-color); font-size: 20px; flex: 0 0 auto; }
.cal-report-file b { font-size: 12px; color: var(--text); display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cal-report-file small { display: block; font-size: 10px; color: var(--muted); margin-top: 2px; }
.cal-report-actions { display: flex; gap: 10px; margin-top: 12px; }
.cal-report-actions .el-button { flex: 1; }
</style>
