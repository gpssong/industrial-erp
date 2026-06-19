import { ref } from 'vue'
import { configApi } from '@/api/system'

// 模块级单例，跨组件共享
const taxSeparation = ref('false')

export function useTaxSeparation() {
  function loadTaxSeparation() {
    configApi.getByKey('PRICE_TAX_SEPARATION').then(r => {
      taxSeparation.value = r.data === 'true' ? 'true' : 'false'
    }).catch(() => {
      taxSeparation.value = 'false'
    })
  }

  function saveTaxSeparation(val) {
    taxSeparation.value = val
    return configApi.updateValue('PRICE_TAX_SEPARATION', val)
  }

  return { taxSeparation, loadTaxSeparation, saveTaxSeparation }
}
