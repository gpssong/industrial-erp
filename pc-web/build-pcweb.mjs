// esbuild 完整构建 pc-web (绕开 vite, 用 @vue/compiler-sfc 处理 .vue)
import * as sfc from '@vue/compiler-sfc'
import esbuild from 'esbuild'
import path from 'node:path'
import fs from 'node:fs'

const root = '/Users/tongban/Documents/根据前端开发erp 2/erp-system/pc-web'
process.chdir(root)

const vuePlugin = {
  name: 'vue',
  setup(build) {
    build.onResolve({ filter: /\.vue$/ }, (args) => {
      return { path: path.resolve(args.resolveDir, args.path), namespace: 'vue' }
    })
    build.onLoad({ filter: /\.vue$/, namespace: 'vue' }, (args) => {
      const filename = args.path
      const source = fs.readFileSync(filename, 'utf-8')
      const id = path.relative(root, filename)
      const { descriptor, errors } = sfc.parse(source, { filename })
      if (errors.length) return { errors: errors.map(e => ({ text: e.message })) }
      const hasScoped = descriptor.styles.some(s => s.scoped)
      const scriptResult = sfc.compileScript(descriptor, {
        id: JSON.stringify(id),
        inlineTemplate: true,
        templateOptions: { compilerOptions: { scopeId: hasScoped ? `data-v-${id}` : undefined } }
      })
      const styleCodes = descriptor.styles.map((s) => {
        const styleResult = sfc.compileStyle({
          source: s.content, filename, id: JSON.stringify(id), scoped: !!s.scoped
        })
        return styleResult.code
      }).join('\n')
      const contents = [
        scriptResult.content,
        styleCodes ? `const __sfc_main__styles = ${JSON.stringify(styleCodes)};` : ''
      ].join('\n')
      return { contents, loader: 'js', resolveDir: path.dirname(filename) }
    })
  }
}

const ignoredVue = path.resolve(root, 'node_modules/vue')

await esbuild.build({
  entryPoints: ['src/main.js'],
  bundle: true,
  outdir: 'dist',
  format: 'esm',
  splitting: true,
  target: 'es2020',
  minify: true,
  sourcemap: false,
  loader: { '.png': 'file', '.svg': 'file', '.woff2': 'file', '.ttf': 'file', '.woff': 'file', '.eot': 'file', '.gif': 'file', '.jpg': 'file' },
  define: {
    'process.env.NODE_ENV': '"production"',
    'process.env': '{}',
    '__VUE_OPTIONS_API__': 'true',
    '__VUE_PROD_DEVTOOLS__': 'false',
    '__VUE_PROD_HYDRATION_MISMATCH_DETAILS__': 'false',
  },
  alias: {
    'vue': ignoredVue,
    '@': path.resolve(root, 'src'),
  },
  resolveExtensions: ['.js', '.ts', '.vue', '.json', '.mjs'],
  plugins: [vuePlugin],
  logLevel: 'info',
})
console.log('build done')
