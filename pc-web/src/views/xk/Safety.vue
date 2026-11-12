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
      <el-tab-pane label="首检 (吹膜/制袋)" name="semi">
        <div class="semi-layout">
          <div class="semi-main">
            <!-- 首检分吹膜/制袋两套模板: 阶段由后端按源工序名派生 (BLOW/BAG) -->
            <div class="stage-switch">
              <el-radio-group v-model="semiStage" size="small" @change="onSwitchSemiStage">
                <el-radio-button value="BLOW">吹膜首检</el-radio-button>
                <el-radio-button value="BAG">制袋首检</el-radio-button>
              </el-radio-group>
              <small>{{ semiStage === 'BAG' ? '制袋工序首件检验 (Q/ZPC 表1~表5 · 10 项, 食品级 +3)' : '吹膜工序首件检验 (Q/ZPC 表1~表3 · 6 项, 食品级 +2)' }}</small>
            </div>
            <!-- 记录选择列表 (从 inspectionApi.page FAI + stage 拉取; 点击填充右侧表单) -->
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
                <div v-if="!semiList.length" class="rec-list-empty">暂无{{ semiStage === 'BAG' ? '制袋首检' : '吹膜首检' }}记录</div>
              </div>
            </div>

            <!-- 记录头卡 -->
            <div class="rec-head">
              <div class="rec-head-l">
                <el-tag size="small" effect="plain" round>{{ semiStage === 'BAG' ? '制袋首检' : '吹膜首检' }}</el-tag>
                <h2>{{ semiSel ? semiSel.productName : 'PE 半成品首件检验' }}</h2>
                <small>记录编号 · {{ semiSel?.billNo || '未选择' }} · 工序 {{ semiSel?.processName || '—' }}</small>
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
                <div v-for="r in semiItems" :key="r.id || r.itemCode" class="ct-row">
                  <div class="ct-col c-item">
                    {{ r.itemName }}
                    <small v-if="Number(r.isFoodGradeItem) === 1" class="fg-mark">食品级</small>
                  </div>
                  <div class="ct-col c-req">{{ r.requirement }}</div>
                  <div class="ct-col c-method">{{ r.method }}</div>
                  <div class="ct-col c-result">
                    <el-input v-model="r.resultValue" size="small" :disabled="semiLocked"
                      :placeholder="r.unit ? ('如 ' + r.unit) : '实测/观察'" />
                  </div>
                  <div class="ct-col c-verdict">
                    <el-select v-model="r.passFlag" size="small" :disabled="semiLocked" style="width:92px">
                      <el-option label="待检" :value="-1" />
                      <el-option label="合格" :value="1" />
                      <el-option label="不合格" :value="0" />
                      <el-option v-if="isNaItem(r.itemCode)" label="不适用" :value="2" />
                    </el-select>
                  </div>
                </div>
                <div v-if="!semiItems.length" class="empty-row">无检验项目数据, 请先在 App 现场开首检</div>
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
              <el-button type="primary" :loading="semiSubmitting" @click="onSaveSemi('PASS')" :disabled="!semiSel || semiLocked"><el-icon><Check /></el-icon>逐项判定合格 (PASS)</el-button>
              <el-button type="danger" :loading="semiSubmitting" @click="onSaveSemi('FAIL')" :disabled="!semiSel || semiLocked"><el-icon><CircleClose /></el-icon>不合格 · 冻结批次</el-button>
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
                <div v-for="r in finItems" :key="r.itemCode" class="ct-row">
                  <div class="ct-col c-item">{{ r.itemName }}<small v-if="r.isFoodGradeItem" class="xk-food-flag">食</small></div>
                  <div class="ct-col c-req">{{ r.requirement }}</div>
                  <div class="ct-col c-method">{{ r.method }}</div>
                  <div class="ct-col c-result">
                    <el-input v-model="r.resultValue" size="small" :disabled="finLocked"
                      :placeholder="r.unit ? ('实测值, 单位 ' + r.unit) : '记录实测或核验结论'" />
                  </div>
                  <div class="ct-col c-verdict">
                    <el-select v-model="r.passFlag" size="small" :disabled="finLocked">
                      <el-option label="待检" :value="-1" />
                      <el-option label="合格" :value="1" />
                      <el-option label="不合格" :value="0" />
                      <el-option v-if="isNaItem(r.itemCode)" label="不适用" :value="2" />
                    </el-select>
                  </div>
                </div>
                <div v-if="!finItems.length" class="empty-row">未取到成品检验模板项, 请确认检验单产品/工单信息完整后重选</div>
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
              <el-button type="primary" :loading="finSubmitting" @click="onSaveFin" :disabled="!finSel || finLocked"><el-icon><Check /></el-icon>{{ finLocked ? '检验记录已签发' : '逐项判定并签发检验记录' }}</el-button>
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
      <el-tab-pane label="过程巡检 (吹膜/制袋)" name="pat">
        <div class="semi-layout">
          <div class="semi-main">
            <div class="stage-switch">
              <el-radio-group v-model="patStage" size="small" @change="onSwitchPatStage">
                <el-radio-button value="BLOW">吹膜巡检</el-radio-button>
                <el-radio-button value="BAG">制袋巡检</el-radio-button>
              </el-radio-group>
              <small>{{ patStage === 'BAG' ? '制袋工序过程巡检 (Q/ZPC 表1~表5 · 8 项, 食品级 +3)' : '吹膜工序过程巡检 (Q/ZPC 表1~表3 · 7 项, 食品级 +4)' }}</small>
            </div>
            <div class="rec-list-card">
              <div class="rec-list-head"><b>{{ patStage === 'BAG' ? '制袋巡检' : '吹膜巡检' }}记录</b><small>报工自动生成 · 点击判定/冻结</small></div>
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
                <div v-if="!patList.length" class="rec-list-empty">暂无{{ patStage === 'BAG' ? '制袋巡检' : '吹膜巡检' }}记录 (工单报工后自动生成)</div>
              </div>
            </div>

            <div class="rec-head">
              <div class="rec-head-l">
                <el-tag size="small" effect="plain" round>{{ patStage === 'BAG' ? '制袋巡检' : '吹膜巡检' }}</el-tag>
                <h2>{{ patSel ? (patSel.productName || patSel.productCode || '过程巡检') : '工序过程巡检' }}</h2>
                <small>工单 · {{ patSel?.sourceBillNo || '未选择' }} · 车间 {{ patSel?.workshop || '—' }} · 工序 {{ patSel?.processName || '—' }}</small>
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

            <!-- 巡检检验项 (服务器模板: 吹膜 7+食品4 / 制袋 8+食品3, 逐项实测才能判定) -->
            <div class="check-table-card">
              <div class="ct-head">
                <div class="ct-col c-item">检验项目</div>
                <div class="ct-col c-req">技术要求</div>
                <div class="ct-col c-method">检验方法</div>
                <div class="ct-col c-result">实测结果</div>
                <div class="ct-col c-verdict">判定</div>
              </div>
              <div class="ct-body">
                <div v-for="r in patItems" :key="r.id || r.itemCode" class="ct-row">
                  <div class="ct-col c-item">
                    {{ r.itemName }}
                    <small v-if="Number(r.isFoodGradeItem) === 1" class="fg-mark">食品级</small>
                  </div>
                  <div class="ct-col c-req">{{ r.requirement }}</div>
                  <div class="ct-col c-method">{{ r.method }}</div>
                  <div class="ct-col c-result">
                    <el-input v-model="r.resultValue" size="small" :disabled="patLocked"
                      :placeholder="r.unit ? ('如 ' + r.unit) : '实测/观察'" />
                  </div>
                  <div class="ct-col c-verdict">
                    <el-select v-model="r.passFlag" size="small" :disabled="patLocked" style="width:92px">
                      <el-option label="待检" :value="-1" />
                      <el-option label="合格" :value="1" />
                      <el-option label="不合格" :value="0" />
                      <el-option v-if="isNaItem(r.itemCode)" label="不适用" :value="2" />
                    </el-select>
                  </div>
                </div>
                <div v-if="!patItems.length" class="empty-row">无检验项目数据, 请先在 App 现场开巡检</div>
              </div>
            </div>

            <div class="semi-foot">
              <el-button type="primary" :loading="patSubmitting" @click="onSavePat('PASS')" :disabled="!patSel || patLocked"><el-icon><Check /></el-icon>逐项判定合格 (PASS)</el-button>
              <el-button type="danger" :loading="patSubmitting" @click="onSavePat('FAIL')" :disabled="!patSel || patLocked"><el-icon><CircleClose /></el-icon>不合格 · 冻结批次</el-button>
              <el-button @click="loadPat"><el-icon><Refresh /></el-icon>刷新</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 3. 生产环境检查 (xk_env_check + GB 31603-2015 逐项清单, 与 App 开工确认同源) -->
      <el-tab-pane label="生产环境检查" name="env" lazy>
        <EnvCheckTab ref="envTabRef" />
      </el-tab-pane>

      <!-- 3.5 v1.1.174 XK — 进货查验 (IQC) 内嵌工作流 -->
      <el-tab-pane label="进货查验 (IQC)" name="iqc" lazy>
        <IqcInline />
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
                <div class="tc c-type"><el-tag :type="stageTag(r)" size="small" effect="light" round>{{ stageLabel(r) }}</el-tag></div>
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
      <el-tab-pane label="检验设备校准" name="cal" lazy>
        <CalibrationTab />
      </el-tab-pane>
    </el-tabs>

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
  Download, Plus, Camera, Lock, Check, Document, Search,
  CircleCheckFilled, CircleClose, Refresh
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { inspectionApi } from '@/api/quality'
import IqcInline from './IqcInline.vue'
import EnvCheckTab from './safety/EnvCheckTab.vue'
import CalibrationTab from './safety/CalibrationTab.vue'
import { downloadBlob } from '@/utils/export'
import { pickImageFile } from '@/utils/upload'

// ===== 通用 =====
const tab = ref('semi')
const inspector = ref('李敏')
// v1.1.149 本公司不做印刷: 达因/印刷质量 4 项才有「不适用」
const NA_ITEM_CODES = ['PAT_CORONA', 'OQC_FILM_CORONA', 'BAG_PRINT', 'OQC_BAG_PRINT']
function isNaItem(code) { return NA_ITEM_CODES.includes(String(code || '')) }

// ============== Tab 1: 首检 (FAI) — 吹膜首检 / 制袋首检 ==============
// 阶段不入库, 由后端按源工序名派生 (prd_process.process_name 含制袋 → BAG, 含吹膜 → BLOW)
const semiStage = ref('BLOW')
const semiList = ref([])
const semiSel = ref(null)
const semiItems = ref([])  // 服务器模板项 (Q/ZPC 1-2017 吹膜 6 / 制袋 10, 食品级再追加)
const semiPhotos = ref([])
const semiLocked = computed(() => semiSel.value?.status === 'CHECKED')

async function loadSemi() {
  const r = await inspectionApi.page({ pageNum: 1, pageSize: 20, inspectionType: 'FAI', stage: semiStage.value })
  if (r.code === 200) {
    semiList.value = r.data?.records || []
    semiSel.value = null
    semiItems.value = []
    semiPhotos.value = []
    if (semiList.value.length) await onSelectSemi(semiList.value[0])
  }
}
async function onSwitchSemiStage() {
  await loadSemi()
}
async function onSelectSemi(row) {
  semiSel.value = row
  semiItems.value = []
  semiPhotos.value = []
  try {
    // 服务器模板灌入 (幂等): 吹膜首检/制袋首检各回传自己那套检验项, 不再读 head.items 快照
    const r = await inspectionApi.ensureTemplate(row.id)
    const head = r?.data?.head || row
    semiSel.value = { ...row, ...head }
    semiItems.value = (r?.data?.items || []).map(x => ({ ...x }))
    try { semiPhotos.value = typeof head.photoUrls === 'string' ? JSON.parse(head.photoUrls) : (head.photoUrls || []) }
    catch (e) { semiPhotos.value = [] }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载首检模板失败')
  }
}
const semiSubmitting = ref(false)
async function onSaveSemi(result) {
  if (!semiSel.value) { ElMessage.warning('请先选择一条记录'); return }
  if (semiLocked.value) { ElMessage.warning('该检验单已签发, 记录只读'); return }
  if (!semiItems.value.length) { ElMessage.warning('无检验模板项, 不能签核首检记录'); return }
  const pending = semiItems.value.filter(it => ![0, 1, 2].includes(Number(it.passFlag)))
  if (pending.length) { ElMessage.warning(`还有 ${pending.length} 项未判定: ${pending[0].itemName}`); return }
  const noValue = semiItems.value.filter(it => Number(it.passFlag) !== 2 && !String(it.resultValue || '').trim())
  if (noValue.length) { ElMessage.warning(`${noValue[0].itemName} 缺实测/观察记录`); return }
  const failed = semiItems.value.filter(it => Number(it.passFlag) === 0)
  if (result === 'PASS' && failed.length) { ElMessage.warning('存在不合格项, 请点「不合格 · 冻结批次」'); return }
  semiSubmitting.value = true
  try {
    await inspectionApi.iqcSaveItems(semiSel.value.id, semiItems.value.map(it => ({ ...it })))
    const body = result === 'FAIL'
      ? { result: 'FAIL', disposition: 'FROZEN', reason: '首检不合格项: ' + (failed.map(f => f.itemName).join('、') || '现场判定不合格') }
      : { result: 'PASS', disposition: 'NONE', reason: '首检逐项合格, 放行下道工序' }
    const r = await inspectionApi.check(semiSel.value.id, body)
    if (r.code === 200) { ElMessage.success(result === 'FAIL' ? '已判定不合格并冻结批次' : '已签发首检合格记录'); await loadSemi() }
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
  finItems.value = []
  finPhotos.value = []
  try {
    // v1.1.131 — 走服务器成品检验模板 (OQC): 幂等灌模板 + 回传真检验项, 不再读 head.items JSON 快照
    const r = await inspectionApi.ensureTemplate(row.id)
    const head = r?.data?.head || row
    finSel.value = { ...row, ...head }
    finItems.value = (r?.data?.items || []).map(x => ({ ...x }))
    try { finPhotos.value = typeof head.photoUrls === 'string' ? JSON.parse(head.photoUrls) : (head.photoUrls || []) }
    catch (e) { finPhotos.value = [] }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载成品检验模板失败')
  }
}
const finLocked = computed(() => finSel.value?.status === 'CHECKED')
const finSubmitting = ref(false)
async function onSaveFin() {
  if (!finSel.value) { ElMessage.warning('请先选择一条记录'); return }
  if (finLocked.value) { ElMessage.warning('该检验单已签发, 记录只读'); return }
  if (!finItems.value.length) { ElMessage.warning('无检验模板项, 不能签发出厂检验记录'); return }
  const pending = finItems.value.filter(it => ![0, 1, 2].includes(Number(it.passFlag)))
  if (pending.length) { ElMessage.warning(`还有 ${pending.length} 项未判定: ${pending[0].itemName}`); return }
  const noValue = finItems.value.filter(it => Number(it.passFlag) !== 2 && !String(it.resultValue || '').trim())
  if (noValue.length) { ElMessage.warning(`${noValue[0].itemName} 缺实测/核验记录`); return }
  const failed = finItems.value.filter(it => Number(it.passFlag) === 0)
  finSubmitting.value = true
  try {
    await inspectionApi.iqcSaveItems(finSel.value.id, finItems.value.map(it => ({ ...it })))
    const sample = Number(finSel.value.sampleQty || 0)
    const body = failed.length
      ? { result: 'FAIL', disposition: 'FROZEN', reason: '成品检验不合格项: ' + failed.map(f => f.itemName).join('、') }
      : {
        result: 'PASS', disposition: 'NONE', reason: '成品检验逐项合格, 放行入库',
        sampleQty: String(sample), qualifiedQty: String(sample), unqualifiedQty: '0'
      }
    const r = await inspectionApi.check(finSel.value.id, body)
    if (r.code === 200) { ElMessage.success(failed.length ? '已判定不合格并冻结批次' : '已签发合格检验记录'); await loadFin() }
    else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
  finally { finSubmitting.value = false }
}

// ============== Tab 2.5: 过程巡检 (PAT) — 吹膜巡检 / 制袋巡检 ==============
const patStage = ref('BLOW')
const patList = ref([])
const patSel = ref(null)
const patItems = ref([])  // 服务器模板项 (吹膜 7 / 制袋 8, 食品级再追加)
const patReason = ref('')
const patSubmitting = ref(false)
const patLocked = computed(() => patSel.value?.status === 'CHECKED')
// 数值归一 (PAT 各量字段可能为字符串/null)
function num(v) { const n = Number(v || 0); return isFinite(n) ? n : 0 }
function patResultLabel(r) {
  return { PASS: '合格', PASS_DISPOSITION: '合格(处置)', FAIL: '不合格', FAIL_REJECT: '拒收', HOLD: '待判定' }[r] || (r || '待判定')
}
async function loadPat() {
  const r = await inspectionApi.page({ pageNum: 1, pageSize: 50, inspectionType: 'PAT', stage: patStage.value })
  if (r.code === 200) {
    patList.value = r.data?.records || []
    patSel.value = null
    patItems.value = []
    if (patList.value.length) await onSelectPat(patList.value[0])
  }
}
async function onSwitchPatStage() {
  await loadPat()
}
async function onSelectPat(row) {
  patSel.value = row
  patReason.value = ''
  patItems.value = []
  try {
    const r = await inspectionApi.ensureTemplate(row.id)
    const head = r?.data?.head || row
    patSel.value = { ...row, ...head }
    patItems.value = (r?.data?.items || []).map(x => ({ ...x }))
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载巡检模板失败')
  }
}
async function onSavePat(result) {
  if (!patSel.value) { ElMessage.warning('请先选择一条巡检记录'); return }
  if (patLocked.value) { ElMessage.warning('该巡检单已签发, 记录只读'); return }
  if (!patItems.value.length) { ElMessage.warning('无检验模板项, 不能签核巡检记录'); return }
  const pending = patItems.value.filter(it => ![0, 1, 2].includes(Number(it.passFlag)))
  if (pending.length) { ElMessage.warning(`还有 ${pending.length} 项未判定: ${pending[0].itemName}`); return }
  const noValue = patItems.value.filter(it => Number(it.passFlag) !== 2 && !String(it.resultValue || '').trim())
  if (noValue.length) { ElMessage.warning(`${noValue[0].itemName} 缺实测/观察记录`); return }
  const failed = patItems.value.filter(it => Number(it.passFlag) === 0)
  if (result === 'PASS' && failed.length) { ElMessage.warning('存在不合格项, 请点「不合格 · 冻结批次」'); return }
  patSubmitting.value = true
  try {
    await inspectionApi.iqcSaveItems(patSel.value.id, patItems.value.map(it => ({ ...it })))
    const reason = patReason.value
      || (result === 'FAIL' ? '巡检不合格项: ' + (failed.map(f => f.itemName).join('、') || '现场判定不合格') : '')
    const r = await inspectionApi.check(patSel.value.id, {
      result, disposition: result === 'FAIL' ? 'FROZEN' : 'NONE', reason
    })
    if (r.code === 200) {
      ElMessage.success(result === 'PASS' ? '已判定合格' : '已判定不合格并冻结批次')
      await loadPat()
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
  finally { patSubmitting.value = false }
}

// ============== Tab 4: 检验记录与报告 (v1.1.98 合并 5 类 qms_inspection + 1 类 xk_env_check) ==============
const recQuery = ref('')
const stageFilter = ref('all')
const reportList = ref([])
const recLoading = ref(false)
const recDetailIsEnv = ref(false)  // v1.1.98 弹窗分支路由: env 行 → 调 envCheckApi.detail
// 首检/巡检按工序阶段拆四类; 复合值是「类型_阶段」, 取数时拆成 inspectionType + stage
const STAGE_OPTIONS = [
  { value: 'all',       label: '全部' },
  { value: 'IQC',       label: '原料 IQC' },
  { value: 'FAI_BLOW',  label: '吹膜首检' },
  { value: 'FAI_BAG',   label: '制袋首检' },
  { value: 'PAT_BLOW',  label: '吹膜巡检' },
  { value: 'PAT_BAG',   label: '制袋巡检' },
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
      stage:          rec.stage || '',
      processName:    rec.processName || '',
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
    stage:          '',
    processName:    '',
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
          if (stageFilter.value !== 'all') {
            const m = String(stageFilter.value).match(/^(FAI|PAT)_(BLOW|BAG)$/)
            if (m) { p.inspectionType = m[1]; p.stage = m[2] } else { p.inspectionType = stageFilter.value }
          }
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
// 台账行 → 中文类型: 首检/巡检再按 stage 分成吹膜/制袋
function stageLabel(row) {
  const t = row?.inspectionType
  const st = row?.stage
  if (t === 'FAI') return st === 'BAG' ? '制袋首检' : st === 'BLOW' ? '吹膜首检' : '首检'
  if (t === 'PAT') return st === 'BAG' ? '制袋巡检' : st === 'BLOW' ? '吹膜巡检' : '巡检'
  return ({ IQC: '原料', OQC: '成品', OQC_RETURN: '退货', ENV: '环境' })[t] || t
}
function stageTag(row) {
  return ({ IQC: 'warning', FAI: 'primary', PAT: 'success', OQC: 'info', OQC_RETURN: 'danger', ENV: 'warning' })[row?.inspectionType] || 'info'
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

// ============== 顶部全局按钮 ==============
// 导出质量档案 → 客户端 CSV 导出当前检验记录 (v1.1.98 含 qms 5 类 + xk_env_check, 归一化后字段已对齐).
// 后端暂无 /qms/inspection/export 端点, 先用前端导出满足"下载档案"需求, 不阻塞.
async function onExport() {
  try { await loadReport() } catch (e) {}
  const rows = filteredReportRows.value
  if (!rows.length) { ElMessage.warning('暂无可导出的检验记录'); return }
  const head = ['单号', '检验类型', '产品', '批号', '抽样量', '合格量', '结果', '判定', '检验时间']
  const lines = rows.map(r => [
    r.billNo, stageLabel(r), r.productName, r.batchNo,
    r.sampleQty ?? '', r.qualifiedQty ?? '', r.result || '', r.disposition || '',
    (r.checkTime || r.createTime || '').toString().slice(0, 16)
  ].join('\t'))
  const csv = '﻿' + head.join('\t') + '\n' + lines.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  downloadBlob(blob, '质量检验档案-' + new Date().toISOString().slice(0, 10) + '.csv')
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
  // Tab 1/2 照片添加 → 真上传 /system/upload/file
  const arr = which === 'fin' ? finPhotos : semiPhotos
  if (!arr || arr.value.length >= 6) { ElMessage.warning('最多 6 张照片'); return }
  pickImageFile((url, name) => arr.value.push({ id: (which || 'p') + Date.now(), url, name }))
}

// ============== 初始化: 监听 tab 切换加载 ==============
onMounted(() => { loadSemi(); loadFin(); loadPat() })
// 环境检查 tab 每次进入都要重拉 DRAFT (可能已被 App 开工确认归档); 首次进入由子组件 onMounted 自己加载
const envTabRef = ref(null)
// tab 切换时按需加载 (避免首屏 5 个接口并发)
let prevTab = tab.value
watch(tab, async (t) => {
  if (t === prevTab) return
  prevTab = t
  if (t === 'semi' && !semiList.value.length) await loadSemi()
  if (t === 'finished' && !finList.value.length) await loadFin()
  if (t === 'env' && envTabRef.value) await envTabRef.value.reload()
  if (t === 'report' && !reportList.value.length) await loadReport()
})
</script>

<style scoped lang="scss">
.xk-safety { display: flex; flex-direction: column; gap: 16px; }

.xk-tabs { margin-top: 4px; }
.xk-tabs :deep(.el-tabs__item) { font-size: 14px; }
.xk-tabs :deep(.el-tabs__active-bar) { height: 3px; border-radius: 3px; }

/* ===== 1. 首检 / 巡检 (吹膜 · 制袋) ===== */
.stage-switch {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 10px 16px;
}
.stage-switch small { font-size: 12px; color: var(--muted); }
.fg-mark {
  display: inline-block; margin-left: 6px; padding: 0 5px; border-radius: 4px;
  background: #fff4e6; color: #d97706; font-size: 10px; font-weight: 600;
}
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
/* v1.1.131 成品检验项改为可录入: 实测值/判定单元格容纳控件 */
.c-result { flex: 0 0 180px; color: var(--text); font-weight: 550; white-space: normal; }
.c-verdict { flex: 0 0 110px; text-align: right; white-space: normal; }
.xk-food-flag { display: inline-block; margin-left: 4px; padding: 0 4px; border-radius: 3px;
  font-size: 11px; font-weight: 600; color: #b45309; background: #fef3c7; }
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

</style>
