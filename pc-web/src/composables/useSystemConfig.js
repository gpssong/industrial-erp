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
    return configApi.getByKey('PRICE_TAX_SEPARATION').then(r => {
      if (r.data === 'true' || r.data === 'false') {
        return configApi.update({ configKey: 'PRICE_TAX_SEPARATION', configValue: val })
      } else {
        return configApi.add({ configName: '价税分离模式', configKey: 'PRICE_TAX_SEPARATION', configValue: val, configType: 1, remark: '开启后显示税率、税额等字段，关闭时单价均为含税单价' })
      }
    })
  }

  return { taxSeparation, loadTaxSeparation, saveTaxSeparation }
}
