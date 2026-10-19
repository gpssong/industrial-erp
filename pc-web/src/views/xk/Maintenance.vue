<template>
  <div class="xk-maintenance">
    <!-- 页面标题 (对齐 Figma 设备维保) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产与质量 / 设备维保</div>
        <h1 class="xk-title">设备维保管理</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onExport"><el-icon><Download /></el-icon>导出维保档案</el-button>
        <el-button type="primary" @click="onNewPlan"><el-icon><Plus /></el-icon>新建维保计划</el-button>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>设备编号、维保时间、维保人员为必填项；现场照片随维保档案保存并支持带图打印</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <!-- 4 个 Tab: 设备清单 / 维保计划 / 维保工单 / 维保记录 -->
    <el-tabs v-model="tab" class="mt-tabs">
      <el-tab-pane label="设备清单" name="list" />
      <el-tab-pane label="维保计划" name="plan" />
      <el-tab-pane label="维保工单" name="order" />
      <el-tab-pane label="维保记录" name="record" />
    </el-tabs>

    <!-- 设备清单 (image #24) -->
    <div v-show="tab === 'list'" class="mt-grid">
      <div class="mt-main">
        <div class="mt-card">
          <div class="list-head">
            <div>
              <b>生产设备清单</b>
              <small>共 {{ devices.length }} 台设备 · {{ runCount }} 台运行中 · {{ maintCount }} 台维保中</small>
            </div>
            <div class="list-head-actions">
              <el-input v-model="devSearch" placeholder="搜索设备编号或名称" clearable style="width: 220px">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-select v-model="devTypeFilter" size="default" style="width: 140px">
                <el-option label="全部设备类型" value="all" />
                <el-option label="吹膜机" value="FILM_BLOWING" />
                <el-option label="制袋机" value="BAG_MAKING" />
                <el-option label="印刷机" value="PRINTING" />
                <el-option label="复合机" value="LAMINATING" />
                <el-option label="分切机" value="SLITTING" />
                <el-option label="辅助设备" value="SIDE_SEAL" />
              </el-select>
              <el-button type="primary" :icon="Plus" size="default" @click="onAddDevice">新增设备</el-button>
            </div>
          </div>

          <div class="dev-table-head">
            <span class="dev-col c-dev">设备</span>
            <span class="dev-col c-type">类型 / 位置</span>
            <span class="dev-col c-run">运行时长</span>
            <span class="dev-col c-last">上次维保</span>
            <span class="dev-col c-next">下次维保</span>
            <span class="dev-col c-status">状态</span>
          </div>

          <div class="dev-list" v-loading="loadingDev">
            <div
              v-for="d in filteredDevices"
              :key="d.id"
              class="dev-row"
              :class="{ active: d.id === selDevice?.id }"
              @click="selDevice = d"
            >
              <span class="dev-col c-dev">
                <span class="dev-ico"><el-icon><OfficeBuilding /></el-icon></span>
                <div class="dev-name-box">
                  <b>{{ d.equipmentName }}</b>
                  <small>{{ d.equipmentCode }}</small>
                </div>
              </span>
              <span class="dev-col c-type">
                <div>{{ d.equipmentType }}</div>
                <small>{{ d.location || d.workshop || '-' }}</small>
              </span>
              <span class="dev-col c-run">{{ d.runHoursStr || '-' }}</span>
              <span class="dev-col c-last">{{ d.lastMaintDate || '-' }}</span>
              <span class="dev-col c-next">{{ d.nextMaintDate || '-' }}</span>
              <span class="dev-col c-status">
                <el-tag :type="statusType(d.status)" size="small" effect="light">{{ statusLabel(d.status) }}</el-tag>
              </span>
              <el-button
                v-if="userStore.hasPerm('equipment:master:edit')"
                link type="primary" size="small"
                class="dev-edit-btn"
                @click.stop="onEditDevice(d)"
              >编辑</el-button>
              <el-icon class="dev-arrow"><ArrowRight /></el-icon>
            </div>
            <div v-if="!loadingDev && filteredDevices.length === 0" class="empty-row">暂无设备</div>
          </div>
        </div>
      </div>

      <!-- 右: 设备档案 -->
      <div class="mt-side">
        <div class="eq-archive" v-if="selDevice">
          <img class="eq-archive-photo" :src="selDevice.photoUrl || EQ_PHOTO" alt="设备" />
          <div class="eq-archive-head">
            <div class="eq-archive-kicker">设备档案 · {{ selDevice.equipmentCode }}</div>
            <el-tag :type="statusType(selDevice.status)" size="small" effect="light">{{ statusLabel(selDevice.status) }}</el-tag>
          </div>
          <div class="eq-archive-name">{{ selDevice.equipmentName }}</div>
          <div class="eq-archive-table">
            <div class="eq-row"><small>设备型号</small><b>{{ selDevice.model || '-' }}</b></div>
            <div class="eq-row"><small>设备位置</small><b>{{ selDevice.location || selDevice.workshop || '-' }}</b></div>
            <div class="eq-row"><small>投用日期</small><b>{{ selDevice.installDate || '-' }}</b></div>
            <div class="eq-row"><small>责任人</small><b>{{ selDevice.owner || '-' }}</b></div>
            <div class="eq-row"><small>累计运行</small><b>{{ selDevice.runHoursStr || '-' }}</b></div>
            <div class="eq-row"><small>关联计划</small><b>{{ planCountByDevice(selDevice.id) }} 条</b></div>
          </div>
          <div class="eq-archive-actions">
            <el-button plain @click="tab = 'record'"><el-icon><Clock /></el-icon>查看维保记录</el-button>
            <el-button type="primary" @click="tab = 'plan'"><el-icon><Tools /></el-icon>查看维保计划</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 设备清单 - 新增/编辑设备弹窗 -->
    <el-dialog v-model="addEqVisible" :title="eqForm.id ? '编辑设备' : '新增设备'" width="640" :close-on-click-modal="false" @closed="resetEqForm">
      <el-form :model="eqForm" label-width="100" :rules="eqFormRules" ref="eqFormRef">
        <el-form-item label="设备编号" prop="equipmentCode">
          <el-input v-model="eqForm.equipmentCode" placeholder="如 EQ-001" :disabled="!!eqForm.id" />
          <div v-if="eqForm.id" class="form-hint">设备编号为唯一业务标识, 编辑时不可修改</div>
        </el-form-item>
        <el-form-item label="设备名称" prop="equipmentName">
          <el-input v-model="eqForm.equipmentName" placeholder="如 1#吹膜机" />
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="eqForm.equipmentType" placeholder="请选择" clearable style="width: 100%">
            <el-option label="吹膜机" value="FILM_BLOWING" />
            <el-option label="制袋机" value="BAG_MAKING" />
            <el-option label="印刷机" value="PRINTING" />
            <el-option label="复合机" value="LAMINATING" />
            <el-option label="分切机" value="SLITTING" />
            <el-option label="辅助设备" value="SIDE_SEAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格型号">
          <el-input v-model="eqForm.model" placeholder="如 SJ-65" />
        </el-form-item>
        <el-form-item label="厂家">
          <el-input v-model="eqForm.manufacturer" />
        </el-form-item>
        <el-form-item label="车间">
          <el-input v-model="eqForm.workshop" placeholder="如 一车间" />
        </el-form-item>
        <el-form-item label="安装位置">
          <el-input v-model="eqForm.location" placeholder="如 A 区 3 号" />
        </el-form-item>
        <el-form-item label="投用日期">
          <el-date-picker v-model="eqForm.installDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="选择日期" />
        </el-form-item>
        <el-form-item label="责任人">
          <el-input v-model="eqForm.owner" placeholder="如 张师傅" />
        </el-form-item>
        <el-form-item label="初始状态">
          <el-select v-model="eqForm.status" style="width: 100%">
            <el-option label="运行中" value="ACTIVE" />
            <el-option label="待机" value="IDLE" />
            <el-option label="维保中" value="MAINT" />
            <el-option label="报废" value="SCRAP" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="eqForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="设备照片">
          <el-upload
            v-model:file-list="eqForm.photoFiles"
            :http-request="onEqPhotoUpload"
            :show-file-list="true"
            :auto-upload="true"
            :limit="1"
            :on-exceed="() => ElMessage.warning('最多上传 1 张设备照片')"
            :on-remove="onEqPhotoRemove"
            accept="image/*"
            list-type="picture-card"
            :before-upload="beforeEqPhotoUpload"
          >
            <el-icon><Camera /></el-icon>
            <div class="el-upload__text">上传照片</div>
          </el-upload>
          <div class="form-hint">JPG/PNG ≤ 10MB, 上传后保存到设备并显示在右侧档案</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addEqVisible = false">取消</el-button>
        <el-button type="primary" :loading="addEqSubmitting" @click="onEqSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 维保计划 (image #25) -->
    <div v-show="tab === 'plan'" class="mt-plan">
      <div class="stat-row">
        <div class="stat-card"><small>本月计划</small><div class="stat-num blue">{{ planStat.month }}<span class="unit">项</span></div></div>
        <div class="stat-card green"><small>已完成</small><div class="stat-num">{{ planStat.done }}<span class="unit">项</span></div></div>
        <div class="stat-card orange"><small>待执行</small><div class="stat-num">{{ planStat.todo }}<span class="unit">项</span></div></div>
        <div class="stat-card red"><small>已逾期</small><div class="stat-num">{{ planStat.overdue }}<span class="unit">项</span></div></div>
      </div>

      <div class="mt-card">
        <div class="plan-head">
          <div>
            <b>预防性维保计划</b>
            <small>按设备运行周期自动生成维保工单</small>
          </div>
          <div class="list-head-actions">
            <el-input v-model="planSearch" placeholder="搜索设备编号" clearable style="width: 200px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="planDevFilter" style="width: 150px">
              <el-option label="全部状态" value="all" />
              <el-option label="已激活" value="ACTIVE" />
              <el-option label="草稿" value="DRAFT" />
              <el-option label="已停用" value="DISABLED" />
            </el-select>
            <el-button v-if="userStore.hasPerm('equipment:maintain-plan:add')" type="primary" @click="onNewPlan"><el-icon><Plus /></el-icon>新增</el-button>
          </div>
        </div>
        <el-table :data="filteredPlans" size="default" empty-text="暂无维保计划" v-loading="loadingPlan">
          <el-table-column prop="planNo" label="计划编号" width="150" />
          <el-table-column label="设备" width="180">
            <template #default="{ row }">
              <div>{{ row.equipmentName }}</div>
              <small class="muted">{{ row.equipmentCode }}</small>
            </template>
          </el-table-column>
          <el-table-column prop="maintContent" label="维保内容" min-width="200" />
          <el-table-column label="周期" width="120">
            <template #default="scope">
              <span>每 {{ scope.row.cycleDays }} 天</span>
            </template>
          </el-table-column>
          <el-table-column prop="nextRunDate" label="下次执行" width="130" />
          <el-table-column prop="owner" label="负责人" width="100" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="planStatusType(row)" size="small" effect="light">
                {{ planStatusLabel(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="userStore.hasPerm('equipment:maintain-order:add')"
                link type="primary" size="small"
                @click="onGenerateOrder(row)"
              >生成工单</el-button>
              <el-button
                v-if="userStore.hasPerm('equipment:maintain-plan:delete')"
                link type="danger" size="small"
                @click="onDeletePlan(row)"
              >删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 维保记录 (image #26) -->
    <div v-show="tab === 'record'" class="mt-grid">
      <div class="mt-main">
        <div class="mt-card">
          <div class="rec-card-head">
            <div>
              <b>历史维保记录</b>
              <small>近 12 个月共 {{ records.length }} 条记录</small>
            </div>
            <el-input v-model="recSearch" placeholder="搜索设备或记录编号" clearable style="width: 220px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </div>
          <div class="rec-list" v-loading="loadingRec">
            <div
              v-for="r in filteredRecords"
              :key="r.id"
              class="rec-item"
              :class="{ active: r.id === selRecord?.id }"
              @click="selRecord = r"
            >
              <span class="rec-ico"><el-icon><Tools /></el-icon></span>
              <div class="rec-body">
                <b>{{ r.maintContent }}</b>
                <div class="rec-sub">{{ r.recordNo }} · {{ r.completeTime || r.completeDate || '-' }} · {{ r.operator || '-' }}</div>
              </div>
              <el-tag :type="r.reviewer ? 'success' : 'info'" size="small" effect="light">
                {{ r.reviewer ? '已审核' : '待审核' }}
              </el-tag>
              <el-icon class="rec-arrow"><ArrowRight /></el-icon>
            </div>
            <div v-if="!loadingRec && filteredRecords.length === 0" class="empty-row">暂无维保记录</div>
          </div>
        </div>
      </div>

      <div class="mt-side">
        <div class="rec-detail" v-if="selRecord">
          <div class="rec-detail-head">
            <div>
              <div class="rec-detail-kicker">维保记录详情</div>
              <b class="rec-detail-no">{{ selRecord.recordNo }}</b>
              <small>原始记录已归档，不支持物理删除</small>
            </div>
            <el-tag :type="selRecord.reviewer ? 'success' : 'info'" size="small" effect="light">
              {{ selRecord.reviewer ? '已审核' : '待审核' }}
            </el-tag>
          </div>

          <div class="rec-detail-table">
            <div class="eq-row"><small>设备编号</small><b>{{ selRecord.equipmentCode }}</b></div>
            <div class="eq-row"><small>维保项目</small><b>{{ selRecord.maintContent }}</b></div>
            <div class="eq-row"><small>维保人员</small><b>{{ selRecord.operator || '-' }}</b></div>
            <div class="eq-row"><small>完成时间</small><b>{{ selRecord.completeTime || '-' }}</b></div>
            <div class="eq-row"><small>来源工单</small><b>{{ selRecord.orderNo || '-' }}</b></div>
            <div class="eq-row"><small>审核人员</small><b>{{ selRecord.reviewer || '-' }}</b></div>
          </div>

          <div class="rec-result">
            <div class="rec-result-title">处理内容与结果</div>
            <div class="rec-result-text">{{ selRecord.result || '—' }}</div>
          </div>

          <div class="rec-photos" v-if="recordPhotos(selRecord).length">
            <div class="rec-photos-title">现场照片</div>
            <div class="rec-photo-grid">
              <div v-for="(p, i) in recordPhotos(selRecord)" :key="i" class="rec-photo">
                <img :src="p" alt="现场照片" />
                <div class="rec-photo-view"><el-icon><View /></el-icon>查看原图</div>
              </div>
            </div>
          </div>

          <div class="rec-detail-foot">
            <div class="rec-archive-note"><el-icon><Lock /></el-icon>记录与附件已随化归档</div>
            <div>
              <el-button
                v-if="!selRecord.reviewer && userStore.hasPerm('equipment:maintain-record:check')"
                type="primary" size="small" @click="onCheckRecord(selRecord)"
              >审核</el-button>
              <el-button plain @click="onExport"><el-icon><Printer /></el-icon>打印维保档案</el-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 维保工单 (Figma 主体) -->
    <div v-show="tab === 'order'" class="mt-grid">
      <div class="mt-main">
        <!-- 工单 hero -->
        <div class="mt-hero" v-if="selOrder">
          <span class="hero-icon"><el-icon :size="22"><Tools /></el-icon></span>
          <div class="hero-body">
            <b>{{ selOrder.equipmentName }} · {{ selOrder.maintContent }}</b>
            <small>工单号 {{ selOrder.orderNo }} · {{ selOrder.sourceType === 'PLAN' ? '计划生成' : '临时工单' }}</small>
          </div>
          <div class="hero-meta">
            <div class="hero-meta-item"><el-icon><Clock /></el-icon>计划: {{ selOrder.planDate }}</div>
            <div class="hero-meta-item"><el-icon><User /></el-icon>负责人: {{ selOrder.operator || '—' }}</div>
            <div class="hero-meta-item"><el-icon><OfficeBuilding /></el-icon>{{ selOrder.equipmentCode }}</div>
          </div>
        </div>
        <div class="mt-hero muted-hero" v-else>
          <span class="hero-icon"><el-icon :size="22"><Tools /></el-icon></span>
          <div class="hero-body">
            <b>暂无选中工单</b>
            <small>请在下方列表选择工单开始处理, 或点击「新建工单」临时创建</small>
          </div>
        </div>

        <!-- 左侧：工单列表 (始终在 tab 顶部) -->
        <div class="mt-card">
          <div class="plan-head">
            <div>
              <b>维保工单</b>
              <small>共 {{ orders.length }} 张工单 · {{ orderStatTodo }} 待处理 · {{ orderStatDone }} 已完成</small>
            </div>
            <div class="list-head-actions">
              <el-input v-model="orderSearch" placeholder="搜索设备编号" clearable style="width: 200px">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-select v-model="orderStatusFilter" style="width: 140px">
                <el-option label="全部状态" value="all" />
                <el-option label="草稿" value="DRAFT" />
                <el-option label="已派工" value="DISPATCHED" />
                <el-option label="处理中" value="PROCESSING" />
                <el-option label="已完成" value="COMPLETED" />
                <el-option label="已取消" value="CANCELLED" />
              </el-select>
              <el-button v-if="userStore.hasPerm('equipment:maintain-order:add')" type="primary" @click="onNewOrder"><el-icon><Plus /></el-icon>新建工单</el-button>
            </div>
          </div>
          <el-table :data="filteredOrders" size="default" v-loading="loadingOrder" empty-text="暂无工单" @row-click="onPickOrder" highlight-current-row>
            <el-table-column prop="orderNo" label="工单号" width="150" />
            <el-table-column label="设备" width="180">
              <template #default="{ row }">
                <div>{{ row.equipmentName }}</div>
                <small class="muted">{{ row.equipmentCode }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="maintContent" label="维保内容" min-width="180" />
            <el-table-column prop="planDate" label="计划日" width="120" />
            <el-table-column prop="operator" label="负责人" width="100" />
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="orderStatusType(row.status)" size="small" effect="light">{{ orderStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status !== 'COMPLETED' && userStore.hasPerm('equipment:maintain-order:edit')"
                  link type="danger" size="small" @click.stop="onDeleteOrder(row)"
                >删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 维保处理记录 (有选中工单才显示) -->
        <div class="mt-card" v-if="selOrder && selOrder.status !== 'COMPLETED'">
          <div class="mt-card-head">
            <b>维保处理记录</b>
          </div>
          <el-form :model="record" label-position="top" class="mt-form">
            <div class="mt-form-grid">
              <el-form-item label="设备编号 *">
                <el-input :model-value="selOrder.equipmentCode" class="compliance-input" disabled />
              </el-form-item>
              <el-form-item label="维保人员 *">
                <el-input v-model="record.operator" class="compliance-input" placeholder="维保负责人" />
              </el-form-item>
              <el-form-item label="开始时间 *">
                <el-date-picker v-model="record.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
                <div class="form-hint">开始处理时自动记录, 也可手动指定</div>
              </el-form-item>
              <el-form-item label="完成时间">
                <el-input model-value="提交完成后自动生成" disabled />
              </el-form-item>
              <el-form-item label="处理说明 *" class="mt-form-full">
                <el-input v-model="record.description" type="textarea" :rows="3"
                  placeholder="请描述故障原因、备件更换、处理措施..." />
              </el-form-item>
            </div>
          </el-form>
        </div>

        <!-- 维保现场照片 (有选中工单才显示) -->
        <div class="mt-card" v-if="selOrder && selOrder.status !== 'COMPLETED'">
          <div class="mt-card-head">
            <b>维保现场照片</b>
            <span class="xk-badge" :class="photos.length ? 'xk-badge-green' : 'xk-badge-gray'">{{ photos.length }}/6</span>
          </div>
          <div class="mt-card-sub">提交后与单据强绑定 (最多 6 张)</div>
          <div class="photo-grid">
            <div v-for="p in photos" :key="p.id" class="photo-item">
              <img :src="p.url" :alt="p.name" />
              <el-icon class="photo-del" @click="photos.splice(photos.indexOf(p), 1)"><Close /></el-icon>
            </div>
            <div v-for="i in (6 - photos.length)" :key="`empty-${i}`" class="photo-item empty" @click="onAddPhoto">
              <el-icon class="camera-icon"><Camera /></el-icon>
              <div class="empty-text">拍照 / 上传</div>
              <div class="empty-hint">JPG、PNG</div>
            </div>
          </div>
          <div class="binding-note">
            <el-icon><Lock /></el-icon>
            <span>附件随业务单据归档，不支持脱离单据单独删除</span>
          </div>
        </div>
      </div>

      <!-- 右: 设备台账 -->
      <div class="mt-side" v-if="selOrder">
        <div class="mt-eq-photo">
          <img :src="selOrderPhoto(selOrder)" alt="设备现场" />
        </div>
        <div class="mt-eq-info">
          <el-tag :type="orderStatusType(selOrder.status)" size="small" effect="light">{{ orderStatusLabel(selOrder.status) }}</el-tag>
          <div class="eq-name">{{ selOrder.equipmentCode }}</div>
          <div class="eq-model">{{ selOrder.equipmentName }}</div>
          <div class="eq-table">
            <div class="eq-row"><small>维保内容</small><b style="font-size:12px;line-height:1.4;text-align:right;max-width:60%">{{ selOrder.maintContent }}</b></div>
            <div class="eq-row"><small>来源</small><b>{{ selOrder.sourceType === 'PLAN' ? '计划生成' : '临时工单' }}</b></div>
            <div class="eq-row"><small>负责人</small><b>{{ selOrder.operator || '—' }}</b></div>
            <div class="eq-row"><small>完成时间</small><b>{{ selOrder.completeTime || '—' }}</b></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部动作 (仅维保工单 tab 且有选中工单) -->
    <div v-show="tab === 'order'" class="mt-footer" v-if="selOrder && selOrder.status !== 'COMPLETED'">
      <el-button @click="onSaveDraft">保存草稿</el-button>
      <el-button type="primary" :loading="submitting" @click="onComplete"><el-icon><CircleCheck /></el-icon>完成维保</el-button>
    </div>

    <!-- 新建/编辑计划 弹窗 -->
    <el-dialog v-model="planDialog" :title="planForm.id ? '编辑维保计划' : '新建维保计划'" width="640px" destroy-on-close>
      <el-form :model="planForm" label-width="100px">
        <el-form-item label="计划编号">
          <el-input v-model="planForm.planNo" placeholder="留空自动生成" />
        </el-form-item>
        <el-form-item label="计划名称 *" >
          <el-input v-model="planForm.planName" placeholder="如: 2#吹膜机 月度滤网保养" />
        </el-form-item>
        <el-form-item label="设备 *">
          <el-select v-model="planForm.equipmentId" placeholder="请选择设备" filterable style="width:100%" @change="onPlanEqChange">
            <el-option v-for="d in devices" :key="d.id" :label="`${d.equipmentCode} · ${d.equipmentName}`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="维保内容 *">
          <el-input v-model="planForm.maintContent" type="textarea" :rows="2" placeholder="如: 更换滤网、清理模头" />
        </el-form-item>
        <el-form-item label="周期类型">
          <el-select v-model="planForm.cycleType" style="width:100%">
            <el-option label="每天" value="DAILY" />
            <el-option label="每周" value="WEEKLY" />
            <el-option label="每月" value="MONTHLY" />
            <el-option label="每季" value="QUARTERLY" />
            <el-option label="每年" value="YEARLY" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期天数 *" v-if="planForm.cycleType === 'CUSTOM'">
          <el-input-number v-model="planForm.cycleDays" :min="1" :step="1" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="planForm.owner" placeholder="责任人" />
        </el-form-item>
        <el-form-item label="下次执行日">
          <el-date-picker v-model="planForm.nextRunDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="planForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" :loading="submittingPlan" @click="onSavePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建工单 弹窗 -->
    <el-dialog v-model="orderDialog" title="新建维保工单" width="640px" destroy-on-close>
      <el-form :model="orderForm" label-width="100px">
        <el-form-item label="来源">
          <el-radio-group v-model="orderForm.sourceType">
            <el-radio value="MANUAL">临时工单</el-radio>
            <el-radio value="PLAN">从计划生成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="设备 *" v-if="orderForm.sourceType === 'MANUAL'">
          <el-select v-model="orderForm.equipmentId" placeholder="请选择设备" filterable style="width:100%" @change="onOrderEqChange">
            <el-option v-for="d in devices" :key="d.id" :label="`${d.equipmentCode} · ${d.equipmentName}`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联计划 *" v-else>
          <el-select v-model="orderForm.planId" placeholder="请选择计划" filterable style="width:100%" @change="onOrderPlanChange">
            <el-option v-for="p in activePlans" :key="p.id" :label="`${p.planNo} · ${p.equipmentName}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="维保内容 *">
          <el-input v-model="orderForm.maintContent" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="计划执行日 *">
          <el-date-picker v-model="orderForm.planDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="orderForm.operator" placeholder="维保负责人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orderDialog = false">取消</el-button>
        <el-button type="primary" :loading="submittingOrder" @click="onSaveOrder">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheckFilled, CircleCheck, Download, Plus, Tools,
  Clock, User, OfficeBuilding, Camera, Lock, Search, ArrowRight, View, Printer, Close
} from '@element-plus/icons-vue'
import {
  equipmentApi, maintainPlanApi, maintainOrderApi, maintainRecordApi
} from '@/api/equipment'
import { uploadFile } from '@/api/workflow'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const tab = ref('list')
const EQ_PHOTO = 'https://images.unsplash.com/photo-1565514020179-026b92b84bb6?w=400'

// ============== 设备清单 ==============
const devices = ref([])
const loadingDev = ref(false)
const devSearch = ref('')
const devTypeFilter = ref('all')
const selDevice = ref(null)
const runCount = computed(() => devices.value.filter(d => d.status === 'ACTIVE').length)
const maintCount = computed(() => devices.value.filter(d => d.status === 'MAINT').length)

const filteredDevices = computed(() => {
  const q = devSearch.value.trim().toLowerCase()
  let list = devices.value
  if (devTypeFilter.value !== 'all') list = list.filter(d => d.equipmentType === devTypeFilter.value)
  if (q) list = list.filter(d => d.equipmentName.toLowerCase().includes(q) || d.equipmentCode.toLowerCase().includes(q))
  return list
})

// ===== 新增设备弹窗 =====
const addEqVisible = ref(false)
const addEqSubmitting = ref(false)
const eqFormRef = ref(null)
const emptyEqForm = () => ({
  id: null,
  equipmentCode: '',
  equipmentName: '',
  equipmentType: 'FILM_BLOWING',
  model: '',
  manufacturer: '',
  workshop: '',
  location: '',
  installDate: null,
  owner: '',
  status: 'ACTIVE',
  remark: '',
  photoUrl: '',
  photoFiles: []  // el-upload 内部态
})
const eqForm = ref(emptyEqForm())
const eqFormRules = {
  equipmentCode: [{ required: true, message: '请输入设备编号', trigger: 'blur' }],
  equipmentName: [{ required: true, message: '请输入设备名称', trigger: 'blur' }]
}

/** 设备照片上传: 沿用 /system/upload/file (CLAUDE.md v1.1.79 工作流模块共用). */
async function onEqPhotoUpload(req) {
  try {
    const url = await uploadFile(req.file)
    req.onSuccess({ url }, req.file)
  } catch (e) {
    req.onError(e)
    ElMessage.error((e && e.message) || '设备照片上传失败')
  }
}
function beforeEqPhotoUpload(file) {
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('设备照片不能超过 10MB')
    return false
  }
  return true
}
function onEqPhotoRemove() {
  eqForm.value.photoUrl = ''
}
// 自定义上传 onSuccess 后, el-upload v-model:file-list 会有 url — 同步回 photoUrl 提交字段
watch(() => eqForm.value.photoFiles, (files) => {
  if (files && files.length > 0 && files[0].response && files[0].response.url) {
    eqForm.value.photoUrl = files[0].response.url
  } else if (files && files.length > 0 && files[0].url && !eqForm.value.photoUrl) {
    // 兜底: 偶发直接拿到 url
    eqForm.value.photoUrl = files[0].url
  }
}, { deep: true })

function onAddDevice() {
  addEqVisible.value = true
}
/** 编辑设备: 复用新增弹窗, id 非空时 EquipmentService.save 走 updateById 分支 */
function onEditDevice(row) {
  // 拷贝响应式对象, 避免污染原设备行
  eqForm.value = {
    ...emptyEqForm(),
    id: row.id,
    equipmentCode: row.equipmentCode || '',
    equipmentName: row.equipmentName || '',
    equipmentType: row.equipmentType || 'FILM_BLOWING',
    model: row.model || '',
    manufacturer: row.manufacturer || '',
    workshop: row.workshop || '',
    location: row.location || '',
    installDate: row.installDate || null,
    owner: row.owner || '',
    status: row.status || 'ACTIVE',
    remark: row.remark || '',
    photoUrl: row.photoUrl || '',
    // 已有 photoUrl 时, 用 file-list 渲染缩略图
    photoFiles: row.photoUrl ? [{ name: 'device-photo', url: row.photoUrl, response: { url: row.photoUrl } }] : []
  }
  addEqVisible.value = true
}
function resetEqForm() {
  eqForm.value = emptyEqForm()
  if (eqFormRef.value) eqFormRef.value.clearValidate()
}
async function onEqSubmit() {
  if (!eqFormRef.value) return
  try { await eqFormRef.value.validate() } catch (e) { return }
  addEqSubmitting.value = true
  try {
    const r = await equipmentApi.save(eqForm.value)
    if (r.code === 200) {
      ElMessage.success(eqForm.value.id ? '设备已更新' : '设备已保存')
      addEqVisible.value = false
      await loadDevices()
    } else {
      ElMessage.error(r.msg || '保存失败')
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    addEqSubmitting.value = false
  }
}

function statusLabel(s) {
  return ({ ACTIVE: '运行中', MAINT: '维保中', IDLE: '待机', SCRAP: '报废' })[s] || s || '-'
}
function statusType(s) {
  return ({ ACTIVE: 'success', MAINT: 'warning', IDLE: 'info', SCRAP: 'danger' })[s] || 'info'
}

async function loadDevices() {
  loadingDev.value = true
  try {
    const r = await equipmentApi.listAll()
    if (r.code === 200) devices.value = r.data || []
    if (!selDevice.value && devices.value.length) selDevice.value = devices.value[0]
  } catch (e) { /* ignore */ } finally { loadingDev.value = false }
}

// ============== 维保计划 ==============
const plans = ref([])
const loadingPlan = ref(false)
const planSearch = ref('')
const planDevFilter = ref('all')

const filteredPlans = computed(() => {
  const q = planSearch.value.trim().toLowerCase()
  let list = plans.value
  if (planDevFilter.value !== 'all') list = list.filter(p => p.status === planDevFilter.value)
  if (q) list = list.filter(p => (p.equipmentCode || '').toLowerCase().includes(q) || (p.equipmentName || '').toLowerCase().includes(q))
  return list
})

const planStat = computed(() => {
  const today = new Date()
  const m = today.getMonth(), y = today.getFullYear()
  let month = 0, done = 0, todo = 0, overdue = 0
  for (const p of plans.value) {
    if (p.status !== 'ACTIVE') continue
    month++
    if (p.nextRunDate) {
      const d = new Date(p.nextRunDate)
      if (d < today) overdue++
      else todo++
    }
  }
  for (const o of orders.value) {
    if (o.status === 'COMPLETED') done++
  }
  return { month, done, todo, overdue }
})

function planStatusType(p) {
  if (p.status === 'DRAFT') return 'info'
  if (p.status === 'DISABLED') return 'danger'
  if (p.nextRunDate && new Date(p.nextRunDate) < new Date()) return 'danger'
  return 'warning'
}
function planStatusLabel(p) {
  if (p.status === 'DRAFT') return '草稿'
  if (p.status === 'DISABLED') return '已停用'
  if (p.nextRunDate && new Date(p.nextRunDate) < new Date()) return '已逾期'
  return '待执行'
}
function planCountByDevice(eqId) {
  return plans.value.filter(p => p.equipmentId === eqId && p.status === 'ACTIVE').length
}

async function loadPlans() {
  loadingPlan.value = true
  try {
    const r = await maintainPlanApi.page({ pageNum: 1, pageSize: 200 })
    if (r.code === 200) plans.value = r.data?.records || []
  } catch (e) { /* ignore */ } finally { loadingPlan.value = false }
}

// 计划弹窗
const planDialog = ref(false)
const submittingPlan = ref(false)
const planForm = ref({ id: null, planNo: '', planName: '', equipmentId: null, maintContent: '', cycleType: 'MONTHLY', cycleDays: 30, owner: '', nextRunDate: null, status: 'ACTIVE', remark: '' })
function resetPlanForm() {
  planForm.value = { id: null, planNo: '', planName: '', equipmentId: null, maintContent: '', cycleType: 'MONTHLY', cycleDays: 30, owner: '', nextRunDate: null, status: 'ACTIVE', remark: '' }
}
function onNewPlan() { resetPlanForm(); planDialog.value = true }
function onPlanEqChange() { /* 冗余字段由后端回填 */ }
async function onSavePlan() {
  if (!planForm.value.planName) { ElMessage.warning('请填写计划名称'); return }
  if (!planForm.value.equipmentId) { ElMessage.warning('请选择设备'); return }
  if (!planForm.value.maintContent) { ElMessage.warning('请填写维保内容'); return }
  submittingPlan.value = true
  try {
    const r = await maintainPlanApi.save(planForm.value)
    if (r.code === 200) {
      ElMessage.success(planForm.value.id ? '已更新计划' : '已新建计划')
      planDialog.value = false
      await loadPlans()
    } else {
      ElMessage.error(r.msg || '保存失败')
    }
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { submittingPlan.value = false }
}
async function onDeletePlan(row) {
  try {
    await ElMessageBox.confirm(`确定删除计划 ${row.planNo}?`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    const r = await maintainPlanApi.delete(row.id)
    if (r.code === 200) { ElMessage.success('已删除'); await loadPlans() }
    else ElMessage.error(r.msg || '删除失败')
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

// ============== 维保工单 ==============
const orders = ref([])
const loadingOrder = ref(false)
const orderSearch = ref('')
const orderStatusFilter = ref('all')
const selOrder = ref(null)
const record = ref({ operator: '', startTime: '', description: '' })
const photos = ref([])
const submitting = ref(false)

const filteredOrders = computed(() => {
  const q = orderSearch.value.trim().toLowerCase()
  let list = orders.value
  if (orderStatusFilter.value !== 'all') list = list.filter(o => o.status === orderStatusFilter.value)
  if (q) list = list.filter(o => (o.equipmentCode || '').toLowerCase().includes(q) || (o.equipmentName || '').toLowerCase().includes(q))
  return list
})
const orderStatTodo = computed(() => orders.value.filter(o => o.status !== 'COMPLETED' && o.status !== 'CANCELLED').length)
const orderStatDone = computed(() => orders.value.filter(o => o.status === 'COMPLETED').length)

function orderStatusLabel(s) {
  return ({ DRAFT: '草稿', DISPATCHED: '已派工', PROCESSING: '处理中', COMPLETED: '已完成', CANCELLED: '已取消' })[s] || s || '-'
}
function orderStatusType(s) {
  return ({ DRAFT: 'info', DISPATCHED: 'primary', PROCESSING: 'warning', COMPLETED: 'success', CANCELLED: 'danger' })[s] || 'info'
}
function selOrderPhoto(o) { return EQ_PHOTO }

async function loadOrders() {
  loadingOrder.value = true
  try {
    const r = await maintainOrderApi.page({ pageNum: 1, pageSize: 200 })
    if (r.code === 200) orders.value = r.data?.records || []
    if (!selOrder.value && orders.value.length) {
      onPickOrder(orders.value[0])
    }
  } catch (e) { /* ignore */ } finally { loadingOrder.value = false }
}

function onPickOrder(row) {
  selOrder.value = row
  record.value = { operator: row.operator || '', startTime: row.startTime || '', description: row.description || '' }
  photos.value = []
}
function onNewOrder() {
  orderForm.value = { id: null, sourceType: 'MANUAL', planId: null, equipmentId: null, maintContent: '', planDate: new Date().toISOString().slice(0, 10), operator: '' }
  orderDialog.value = true
}
function onOrderEqChange() {
  const d = devices.value.find(x => x.id === orderForm.value.equipmentId)
  if (d) orderForm.value.operator = d.owner || ''
}
function onOrderPlanChange() {
  const p = plans.value.find(x => x.id === orderForm.value.planId)
  if (p) {
    orderForm.value.equipmentId = p.equipmentId
    orderForm.value.maintContent = p.maintContent
    orderForm.value.operator = p.owner || ''
    if (p.nextRunDate) orderForm.value.planDate = p.nextRunDate
  }
}
const activePlans = computed(() => plans.value.filter(p => p.status === 'ACTIVE'))
const orderDialog = ref(false)
const submittingOrder = ref(false)
const orderForm = ref({ id: null, sourceType: 'MANUAL', planId: null, equipmentId: null, maintContent: '', planDate: '', operator: '' })
async function onSaveOrder() {
  if (!orderForm.value.maintContent) { ElMessage.warning('请填写维保内容'); return }
  if (orderForm.value.sourceType === 'PLAN' && !orderForm.value.planId) { ElMessage.warning('请选择计划'); return }
  if (orderForm.value.sourceType === 'MANUAL' && !orderForm.value.equipmentId) { ElMessage.warning('请选择设备'); return }
  submittingOrder.value = true
  try {
    const r = await maintainOrderApi.save(orderForm.value)
    if (r.code === 200) {
      ElMessage.success('工单已创建')
      orderDialog.value = false
      await loadOrders()
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { submittingOrder.value = false }
}

async function onDeleteOrder(row) {
  try {
    await ElMessageBox.confirm(`确定删除工单 ${row.orderNo}?`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    const r = await maintainOrderApi.delete(row.id)
    if (r.code === 200) { ElMessage.success('已删除'); if (selOrder.value?.id === row.id) selOrder.value = null; await loadOrders() }
    else ElMessage.error(r.msg || '删除失败')
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

function onAddPhoto() {
  if (photos.value.length >= 6) { ElMessage.warning('最多 6 张照片'); return }
  const inp = document.createElement('input')
  inp.type = 'file'; inp.accept = 'image/*'
  inp.onchange = async () => {
    const f = inp.files && inp.files[0]
    if (!f) return
    try {
      const url = await uploadFile(f)
      photos.value.push({ id: 'm' + Date.now(), url, name: f.name })
      ElMessage.success('照片已上传')
    } catch (e) { ElMessage.error((e && e.message) || '上传失败') }
  }
  inp.click()
}
async function onSaveDraft() {
  if (!selOrder.value) return
  submitting.value = true
  try {
    const payload = { ...selOrder.value, operator: record.value.operator, startTime: record.value.startTime, description: record.value.description, photoUrls: JSON.stringify(photos.value) }
    const r = await maintainOrderApi.save(payload)
    if (r.code === 200) { ElMessage.success('已保存草稿'); await loadOrders() }
    else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error(e.message || '保存失败') }
  finally { submitting.value = false }
}
async function onComplete() {
  if (!selOrder.value) return
  if (!record.value.operator) { ElMessage.warning('请填写维保人员'); return }
  if (!record.value.startTime) { ElMessage.warning('请选择开始时间'); return }
  if (!record.value.description) { ElMessage.warning('请填写处理说明'); return }
  try {
    await ElMessageBox.confirm(`确认完成工单 ${selOrder.value.orderNo}? 该操作将生成归档记录。`, '完成确认', { type: 'warning', confirmButtonText: '确认完成', cancelButtonText: '取消' })
  } catch { return }
  submitting.value = true
  try {
    // 1. 先保存草稿 (含 operator/description/photoUrls)
    const draftPayload = { ...selOrder.value, operator: record.value.operator, startTime: record.value.startTime, description: record.value.description, photoUrls: JSON.stringify(photos.value), status: 'PROCESSING' }
    const sr = await maintainOrderApi.save(draftPayload)
    if (sr.code !== 200) { ElMessage.error(sr.msg || '保存失败'); return }
    // 2. 完成 (写 record)
    const cr = await maintainOrderApi.complete(selOrder.value.id, { result: record.value.description, reviewer: record.value.operator })
    if (cr.code === 200) {
      ElMessage.success('维保已完成, 已归档记录 ' + (cr.data?.recordNo || ''))
      selOrder.value = null
      record.value = { operator: '', startTime: '', description: '' }
      photos.value = []
      await loadOrders()
      await loadPlans()
      await loadDevices()
    } else {
      ElMessage.error(cr.msg || '完成失败')
    }
  } catch (e) { ElMessage.error(e.message || '完成失败') }
  finally { submitting.value = false }
}

// 从计划生成工单
function onGenerateOrder(plan) {
  orderForm.value = { id: null, sourceType: 'PLAN', planId: plan.id, equipmentId: plan.equipmentId, maintContent: plan.maintContent, planDate: plan.nextRunDate || new Date().toISOString().slice(0, 10), operator: plan.owner || '' }
  orderDialog.value = true
}

// ============== 维保记录 ==============
const records = ref([])
const loadingRec = ref(false)
const recSearch = ref('')
const selRecord = ref(null)

const filteredRecords = computed(() => {
  const q = recSearch.value.trim().toLowerCase()
  if (!q) return records.value
  return records.value.filter(r => (r.recordNo || '').toLowerCase().includes(q) || (r.equipmentCode || '').toLowerCase().includes(q) || (r.maintContent || '').toLowerCase().includes(q))
})

function recordPhotos(r) {
  if (!r.photoUrls) return []
  try {
    const arr = JSON.parse(r.photoUrls)
    if (Array.isArray(arr) && arr.length) return arr.map(x => x.url || x)
  } catch { /* 可能是 JSON 数组字符串 */ }
  return [EQ_PHOTO]
}

async function loadRecords() {
  loadingRec.value = true
  try {
    const r = await maintainRecordApi.page({ pageNum: 1, pageSize: 100 })
    if (r.code === 200) records.value = r.data?.records || []
    if (!selRecord.value && records.value.length) selRecord.value = records.value[0]
  } catch (e) { /* ignore */ } finally { loadingRec.value = false }
}

async function onCheckRecord(row) {
  try {
    const r = await maintainRecordApi.check(row.id, { reviewer: userStore.userInfo?.nickname || userStore.userInfo?.username || '审核人' })
    if (r.code === 200) { ElMessage.success('已审核'); selRecord.value = r.data; await loadRecords() }
    else ElMessage.error(r.msg || '审核失败')
  } catch (e) { ElMessage.error(e.message || '审核失败') }
}

// 导出维保档案 → 客户端 CSV 导出当前维保工单 + 归档记录 (数据已由 /em/maintain-* /page 拉取)
async function onExport() {
  try { if (!orders.value.length) await loadOrders() } catch (e) {}
  try { if (!records.value.length) await loadRecords() } catch (e) {}
  const head = ['类型', '单号', '设备', '内容', '结果', '状态', '时间']
  const lines = []
  ;(orders.value || []).forEach(o => lines.push(['工单', o.recordNo, o.deviceName || o.deviceCode || '-', o.maintType || o.maintContent || '', '', o.billStatus, (o.maintDate || '').toString().slice(0, 16)].join('\t')))
  ;(records.value || []).forEach(r => lines.push(['归档记录', r.recordNo, r.equipmentCode || r.deviceName || '-', r.maintContent || '', r.result || '', r.billStatus, (r.completeTime || r.createTime || '').toString().slice(0, 16)].join('\t')))
  if (!lines.length) { ElMessage.warning('暂无可导出的维保档案'); return }
  const csv = '﻿' + head.join('\t') + '\n' + lines.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '设备维保档案-' + new Date().toISOString().slice(0, 10) + '.csv'
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出 ' + lines.length + ' 条维保档案')
}

watch(tab, (t) => {
  if (t === 'plan' && !plans.value.length) loadPlans()
  if (t === 'order' && !orders.value.length) loadOrders()
  if (t === 'record' && !records.value.length) loadRecords()
})

onMounted(async () => {
  await loadDevices()
  // 预热其他 3 个 tab 数据, 切换无延迟
  loadPlans()
  loadOrders()
  loadRecords()
})
</script>

<style scoped lang="scss">
.xk-maintenance { display: flex; flex-direction: column; gap: 16px; }

// Tab 视觉 (Figma 下划线式)
.mt-tabs { margin-top: 4px; }
.mt-tabs :deep(.el-tabs__item) { font-size: 14px; }

.mt-grid {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1100px) { .mt-grid { grid-template-columns: 1fr; } }
.mt-main { display: flex; flex-direction: column; gap: 14px; }

// 工单 hero
.mt-hero {
  display: flex; align-items: flex-start; gap: 14px;
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px 20px;
}
.muted-hero { opacity: .6; }
.hero-icon {
  width: 46px; height: 46px; border-radius: 12px; flex: 0 0 auto;
  display: grid; place-items: center;
  background: var(--primary-soft, #eef6ff); color: var(--primary-color);
}
.hero-body { flex: 1; min-width: 0; }
.hero-body b { display: block; font-size: 15px; font-weight: 730; color: var(--text); }
.hero-body small { display: block; font-size: 12px; color: var(--muted); margin-top: 5px; line-height: 1.55; }
.hero-meta { display: flex; gap: 18px; margin-top: 12px; flex-wrap: wrap; }
.hero-meta-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #526174;
  .el-icon { color: var(--muted); }
}

// 卡片
.mt-card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px 20px;
}
.mt-card-head {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px;
  b { font-size: 15px; font-weight: 730; color: var(--text); }
}
.mt-card-sub { font-size: 12px; color: var(--muted); margin: -8px 0 12px; }
.empty-row { padding: 30px; text-align: center; color: var(--muted); font-size: 12px; }
.muted { color: var(--muted, #6f7d90); }

// 表单
.mt-form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px 16px; }
@media (max-width: 800px) { .mt-form-grid { grid-template-columns: 1fr; } }
.mt-form :deep(.el-form-item) { margin-bottom: 14px; }
.mt-form :deep(.el-form-item__label) { font-size: 12px; color: #526174; font-weight: 600; }
.mt-form-full { grid-column: 1 / -1; }
.form-hint { font-size: 11px; color: var(--muted); margin-top: 5px; }
.compliance-input :deep(.el-input__wrapper) { background: #f8fbff; box-shadow: 0 0 0 1px #8eb9e7 inset; }

// 照片
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
@media (max-width: 700px) { .photo-grid { grid-template-columns: repeat(2, 1fr); } }
.photo-item {
  position: relative; aspect-ratio: 1.15; border-radius: 9px; overflow: hidden;
  background: #e9eef3; border: 1px solid #dfe6ef;
  img { width: 100%; height: 100%; object-fit: cover; }
  .photo-del {
    position: absolute; top: 6px; right: 6px; width: 23px; height: 23px;
    background: rgba(8, 20, 34, .78); color: #fff; border-radius: 50%;
    display: grid; place-items: center; font-size: 12px; cursor: pointer;
  }
  &.empty {
    border: 1px dashed #aebdcc; background: #f8fafc; color: var(--muted);
    display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
    cursor: pointer; transition: .18s;
    .camera-icon { font-size: 24px; color: var(--primary-color); }
    .empty-text { font-size: 12px; font-weight: 650; }
    .empty-hint { font-size: 9px; color: #9aa6b4; }
    &:hover { border-color: var(--primary-color); }
  }
}
.binding-note {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; color: #607189;
  background: #f5f8fb; padding: 9px 12px; border-radius: 8px;
  .el-icon { color: var(--primary-color); }
}

// 右: 设备台账
.mt-side { position: sticky; top: 88px; }
.mt-eq-photo { border-radius: 14px 14px 0 0; overflow: hidden; }
.mt-eq-photo img { width: 100%; aspect-ratio: 1.1; object-fit: cover; display: block; }
.mt-eq-info {
  background: #fff; border: 1px solid var(--line); border-top: 0;
  border-radius: 0 0 14px 14px; padding: 14px 16px;
}
.mt-eq-info .el-tag { margin-bottom: 10px; }
.eq-name { font-size: 18px; font-weight: 730; color: var(--text); }
.eq-model { font-size: 12px; color: var(--muted); margin-top: 3px; margin-bottom: 12px; }
.eq-table { display: flex; flex-direction: column; }
.eq-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 9px 0; border-bottom: 1px solid #f0f2f6;
  small { font-size: 12px; color: var(--muted); }
  b { font-size: 13px; font-weight: 700; color: var(--text); }
  &:last-child { border-bottom: 0; }
}

// 底部动作
.mt-footer {
  display: flex; align-items: center; justify-content: flex-end; gap: 10px;
  padding-top: 4px;
}

// ===== 设备清单 (image #24) =====
.list-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 12px; margin-bottom: 14px;
}
.list-head b { font-size: 15px; font-weight: 730; color: var(--text); }
.list-head small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
.list-head-actions { display: flex; gap: 10px; }

.dev-table-head {
  display: grid;
  grid-template-columns: 1.6fr 1fr 0.8fr 0.9fr 0.9fr 0.8fr 60px 24px;
  gap: 8px; padding: 8px 12px; border-bottom: 1px solid #f0f2f6;
}
.dev-col { font-size: 12px; color: var(--muted); font-weight: 600; }
.dev-col.c-dev { display: flex; align-items: center; gap: 10px; }

.dev-list { display: flex; flex-direction: column; }
.dev-row {
  display: grid; grid-template-columns: 1.6fr 1fr 0.8fr 0.9fr 0.9fr 0.8fr 60px 24px;
  gap: 8px; align-items: center;
  padding: 12px; border-bottom: 1px solid #f0f2f6; cursor: pointer; transition: background .15s;
  &:hover { background: #fbfcfe; }
  &.active { background: var(--primary-soft, #f4f8fd); box-shadow: inset 3px 0 var(--primary-color); }
  .dev-col { font-size: 13px; color: var(--text);
    small { display: block; font-size: 11px; color: var(--muted); font-weight: 400; margin-top: 2px; }
    .dev-name-box { display: flex; flex-direction: column;
      b { font-size: 13px; font-weight: 700; color: var(--text); }
      small { font-size: 11px; color: var(--muted); margin-top: 2px; } }
  }
}
.dev-ico {
  width: 38px; height: 38px; border-radius: 9px; flex: 0 0 auto;
  display: grid; place-items: center;
  background: var(--primary-soft, #eef6ff); color: var(--primary-color);
}
.dev-arrow { color: #b6c2d0; }
.dev-edit-btn { font-size: 12px; padding: 4px 6px; }

// 设备档案右侧
.eq-archive {
  background: #fff; border: 1px solid var(--line); border-radius: 14px; overflow: hidden;
  position: sticky; top: 88px;
}
.eq-archive-photo { width: 100%; aspect-ratio: 1.4; object-fit: cover; display: block; }
.eq-archive-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px 0;
}
.eq-archive-kicker { font-size: 12px; color: var(--primary-color); font-weight: 650; }
.eq-archive-name { font-size: 17px; font-weight: 730; color: var(--text); padding: 4px 16px 12px; }
.eq-archive-table { padding: 0 16px; border-top: 1px solid #f0f2f6; }
.eq-archive-table .eq-row { border-bottom: 1px solid #f0f2f6; }
.eq-archive-actions {
  display: flex; gap: 8px; padding: 14px 16px;
  .el-button { flex: 1; margin: 0; }
}

// ===== 维保计划 (image #25) =====
.mt-plan { display: flex; flex-direction: column; gap: 16px; }
.stat-row {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px;
}
@media (max-width: 900px) { .stat-row { grid-template-columns: repeat(2, 1fr); } }
.stat-card {
  position: relative; overflow: hidden;
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 16px 18px;
  &::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--primary-color); }
  &.green::before { background: var(--success); }
  &.orange::before { background: #f5a623; }
  &.red::before { background: var(--danger); }
  small { font-size: 12px; color: var(--muted); }
  .stat-num { font-size: 30px; font-weight: 780; color: var(--text); margin-top: 6px;
    &.blue { color: var(--primary-color); }
    .unit { font-size: 13px; font-weight: 500; color: var(--muted); margin-left: 4px; }
  }
}
.plan-head {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px;
  b { font-size: 15px; font-weight: 730; color: var(--text); }
  small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
}

// ===== 维保记录 (image #26) =====
.rec-card-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px;
  b { font-size: 15px; font-weight: 730; color: var(--text); }
  small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
}
.rec-list { display: flex; flex-direction: column; gap: 8px; }
.rec-item {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px; background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 11px;
  cursor: pointer; transition: all .18s;
  &:hover { background: #f4f8fd; border-color: #cfe0f2; }
  &.active { border-color: var(--primary-color); background: #f4f8fd; box-shadow: inset 3px 0 var(--primary-color); }
}
.rec-ico {
  width: 34px; height: 34px; border-radius: 9px; flex: 0 0 auto;
  display: grid; place-items: center;
  background: var(--primary-soft, #eef6ff); color: var(--primary-color);
}
.rec-body { flex: 1; min-width: 0; }
.rec-body b { display: block; font-size: 13px; font-weight: 700; color: var(--text); }
.rec-sub { font-size: 11px; color: var(--muted); margin-top: 3px; }
.rec-arrow { color: #b6c2d0; }

.rec-detail {
  background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px 20px;
  position: sticky; top: 88px;
}
.rec-detail-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; }
.rec-detail-kicker { font-size: 12px; color: var(--primary-color); font-weight: 650; }
.rec-detail-no { font-size: 16px; font-weight: 730; color: var(--text); margin: 3px 0; }
.rec-detail-head small { display: block; font-size: 11px; color: var(--muted); }
.rec-detail-table { border-top: 1px solid #f0f2f6; margin-bottom: 14px; }
.rec-result { margin-bottom: 14px; }
.rec-result-title { font-size: 13px; font-weight: 700; color: var(--text); margin-bottom: 6px; }
.rec-result-text { font-size: 12px; color: #526174; line-height: 1.7; }
.rec-photos-title { font-size: 13px; font-weight: 700; color: var(--text); margin-bottom: 8px; }
.rec-photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 14px; }
.rec-photo { position: relative; border-radius: 9px; overflow: hidden; aspect-ratio: 1.2; }
.rec-photo img { width: 100%; height: 100%; object-fit: cover; }
.rec-photo-view {
  position: absolute; bottom: 0; left: 0; right: 0;
  background: rgba(8, 20, 34, .6); color: #fff;
  font-size: 11px; text-align: center; padding: 4px 0;
  display: flex; align-items: center; justify-content: center; gap: 4px;
}
.rec-detail-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding-top: 12px; border-top: 1px solid #f0f2f6;
}
.rec-archive-note {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; color: var(--success);
  .el-icon { color: var(--success); }
}
</style>
