/**
 * 补丁: 为 Vue 3.4.x 添加 dcloudio/uni-app alpha 依赖的内部 API
 * - isInSSRComponentSetup: SSR 标志, 默认 false
 * - injectHook: 生命周期钩子注册函数
 * Vue 3.4 已移除这些导出, 但 dcloudio alpha 仍依赖
 */
const fs = require('fs');
const f = 'node_modules/vue/dist/vue.runtime.esm-bundler.js';
try {
  let c = fs.readFileSync(f, 'utf8');
  let patched = false;

  if (!c.includes('isInSSRComponentSetup')) {
    c += '\nexport const isInSSRComponentSetup = false;\n';
    patched = true;
    console.log('[patch-vue] +isInSSRComponentSetup');
  }

  if (!c.includes('export function injectHook')) {
    c += `
export function injectHook(type, hook, target, prepend) {
  if (!target) return;
  if (!target[type]) target[type] = [];
  const wrappedHook = hook.__weh || (hook.__weh = function(args) {
    if (target.isUnmounted) return;
    return hook(args);
  });
  if (prepend) target[type].unshift(wrappedHook);
  else target[type].push(wrappedHook);
}
`;
    patched = true;
    console.log('[patch-vue] +injectHook');
  }

  if (patched) {
    fs.writeFileSync(f, c);
  }
} catch (e) {
  // vue 文件不存在时静默跳过
}
