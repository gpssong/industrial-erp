// v1.1.184: pc-web 首次引入 lint 门禁 (P3).
// 目标不是一次把历史代码全部规整, 而是挡住「运行时才炸」的一类错误:
//   <script setup> 里模板引用了不存在的标识符时, 编译期只生成 _ctx.X, 构建照样绿,
//   线上点开按钮才报错 (空弹窗/死按钮事故的根因)。
// 因此 error 级只放「模板解析 + 未定义属性」这类确定性缺陷, 其余风格类一律 warn,
// 让 `npm run lint` 退出码可信 (warn 不 fail), 不致被历史代码噪声卡死。
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  {
    ignores: ['dist/**', 'dist-new/**', 'release/**', 'node_modules/**'],
  },
  ...pluginVue.configs['flat/base'],
  {
    name: 'erp/pc-web',
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      'vue/no-undef-properties': 'error',
      'vue/no-dupe-keys': 'error',
      'vue/no-parsing-error': 'error',
      'vue/no-use-v-if-with-v-for': 'error',
      'vue/no-unused-vars': 'warn',
      'vue/require-v-for-key': 'warn',
      'vue/no-mutating-props': 'warn',
    },
  },
]
