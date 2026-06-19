import { ref } from 'vue'
import { configApi } from '@/api/system'

const systemName = ref('工业ERP')

export function useSystemName() {
  async function loadSystemName() {
    try {
      const r = await configApi.getByKey('SYS_NAME')
      if (r?.data) systemName.value = r.data
    } catch (e) {
      // 读取失败则使用默认值
    }
  }

  async function saveSystemName(name) {
    await configApi.updateValue('SYS_NAME', name)
    systemName.value = name
  }

  return { systemName, loadSystemName, saveSystemName }
}
