import { ref } from 'vue'
import { configApi } from '@/api/system'

// 品牌 (SYS_NAME) + 工厂名 (SYS_FACTORY) 两个 module-singleton ref, 全局共享
const systemName = ref('工业ERP')
const factoryName = ref('华东一厂')

// 通用: 从 sys_config 读一个键, 有值则覆盖 ref
async function loadKey(key, target, fallback) {
  try {
    const r = await configApi.getByKey(key)
    if (r?.data) target.value = r.data
    else target.value = fallback
  } catch (e) {
    target.value = fallback
  }
}

export function useSystemName() {
  async function loadSystemName() {
    await Promise.all([
      loadKey('SYS_NAME', systemName, '工业ERP'),
      loadKey('SYS_FACTORY', factoryName, '华东一厂')
    ])
  }

  async function saveSystemName(name) {
    await configApi.updateValue('SYS_NAME', name)
    systemName.value = name
  }

  async function saveFactoryName(name) {
    await configApi.updateValue('SYS_FACTORY', name)
    factoryName.value = name
  }

  return { systemName, factoryName, loadSystemName, saveSystemName, saveFactoryName }
}
