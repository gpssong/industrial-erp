<template>
  <div class="code-editor-wrapper">
    <Codemirror
      :model-value="modelValue"
      :placeholder="placeholder"
      :style="{ height: height }"
      :extensions="extensions"
      :tab-size="2"
      :autofocus="false"
      @update:model-value="onUpdate"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Codemirror } from 'vue-codemirror'
import { html } from '@codemirror/lang-html'
import { oneDark } from '@codemirror/theme-one-dark'
import { EditorView } from '@codemirror/view'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  height: { type: String, default: '300px' },
  dark: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'change'])

const extensions = computed(() => {
  const exts = [
    html(),
    EditorView.lineWrapping,
  ]
  if (props.dark) exts.push(oneDark)
  return exts
})

function onUpdate(value) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<style scoped>
.code-editor-wrapper {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
  flex: 1;
  min-height: 0;
}

.code-editor-wrapper :deep(.cm-editor) {
  height: 100%;
  font-size: 13px;
}

.code-editor-wrapper :deep(.cm-scroller) {
  font-family: Consolas, 'Courier New', monospace;
}

.code-editor-wrapper :deep(.cm-content) {
  padding: 8px 0;
}

.code-editor-wrapper :deep(.cm-gutters) {
  background: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}
</style>
