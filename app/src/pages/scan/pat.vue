<template>
  <ProcessInspection v-if="routeReady" :key="routeSig" :order-id="orderId" :process-id="processId" :stage="stage" inspection-type="PAT" />
</template>

<script>
import ProcessInspection from '../../components/ProcessInspection.vue'
import { activeRouteQuery, onActiveRouteChange, setInspectionContext, takePendingRoute } from '../../utils/routeQuery.js'

const PAGE_PATH = '/pages/scan/pat'

export default {
  components: { ProcessInspection },
  data() {
    return { routeApplied: false, routeReady: false, orderId: '', processId: '', stage: '', stopRouteWatch: null }
  },
  computed: {
    routeSig() { return [this.orderId, this.processId, this.stage].join('|') }
  },
  onLoad(query = {}) {
    // v1.1.140 实测: 同路由再次进入时, 框架塞给 onLoad 的 query 可能是「上一趟」的, 且那一刻
    //   location.hash 可能还没落到本次 —— 先取 nav.js 跳转前登记的参数, 其次当前 URL, 最后框架给的。
    this.applyRoute(takePendingRoute(PAGE_PATH) || activeRouteQuery(PAGE_PATH) || query)
  },
  onShow() {
    // 原生 App 端 (无 hashchange) 的兜底; applyRoute 有签名守卫, 重复触发不会重建子组件
    const query = takePendingRoute(PAGE_PATH) || activeRouteQuery(PAGE_PATH)
    if (query) this.applyRoute(query)
  },
  mounted() {
    // 页面实例被复用 (不重建) 时 onLoad 不再触发, 唯一信号是 hashchange/popstate
    this.stopRouteWatch = onActiveRouteChange(PAGE_PATH, (query) => this.applyRoute(query))
  },
  beforeUnmount() {
    if (this.stopRouteWatch) this.stopRouteWatch()
    this.stopRouteWatch = null
  },
  methods: {
    applyRoute(query = {}) {
      const orderId = query.orderId == null ? '' : String(query.orderId)
      const processId = query.processId == null ? '' : String(query.processId)
      const stage = query.stage == null ? '' : String(query.stage).toUpperCase()
      // 每次都登记: 页面实例被复用时子组件 props 会被保住, 组件以这份上下文为准认工序 (见 utils/routeQuery.js)
      setInspectionContext({ inspectionType: 'PAT', orderId, processId, stage })
      if (this.routeApplied && orderId === this.orderId && processId === this.processId && stage === this.stage) return
      this.routeApplied = true
      this.orderId = orderId
      this.processId = processId
      this.stage = stage
      // 换工单/工序就重建子组件: 上一道工序的检验单、已签核缓存和未完成请求不能带过来
      this.routeReady = false
      this.$nextTick(() => { this.routeReady = true })
    }
  }
}
</script>
