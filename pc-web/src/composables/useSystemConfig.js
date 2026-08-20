import { ref } from 'vue'

// 模块级单例, 跨组件共享. v1.1.19+ 价税分离开关已废弃 (price=含税单价,不再拆分).
// taxSeparation 引用保留 ('false') 让旧 import 不报错, load/save 为 no-op 兼容老代码.
const taxSeparation = ref('false')

export function useTaxSeparation() {
  function loadTaxSeparation() {
    /* no-op, 保留兼容. price=含税单价 (v1.1.19+), 不再做价税分离. */
  }
  function saveTaxSeparation(val) {
    /* no-op, 保留兼容. */
    return Promise.resolve()
  }
  return { taxSeparation, loadTaxSeparation, saveTaxSeparation }
}