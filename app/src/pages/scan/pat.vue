<template>
  <ProcessInspection v-if="routeReady" :order-id="orderId" :process-id="processId" :stage="stage" inspection-type="PAT" />
</template>

<script>
import ProcessInspection from '../../components/ProcessInspection.vue'

export default {
  components: { ProcessInspection },
  data() {
    return { routeReady: false, orderId: '', processId: '', stage: '' }
  },
  onLoad(query = {}) {
    this.orderId = query.orderId == null ? '' : String(query.orderId)
    this.processId = query.processId == null ? '' : String(query.processId)
    this.stage = query.stage == null ? '' : String(query.stage).toUpperCase()
    this.routeReady = true
  }
}
</script>
