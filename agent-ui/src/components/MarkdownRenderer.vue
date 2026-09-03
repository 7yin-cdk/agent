<script setup>
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const props = defineProps({ content: { type: String, default: '' } })
const html = computed(() => md.render(props.content || ''))
</script>

<template>
  <div class="markdown-body" v-html="html" />
</template>

<style scoped>
.markdown-body { line-height: 1.7; word-break: break-word; font-size: 14px; }
.markdown-body :deep(p) { margin: 0 0 8px; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) {
  margin: 10px 0 6px; font-weight: 600; line-height: 1.3;
}
.markdown-body :deep(ul), .markdown-body :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.markdown-body :deep(li) { margin: 2px 0; }
.markdown-body :deep(blockquote) { border-left: 3px solid var(--border); margin: 6px 0; padding: 2px 12px; color: var(--text-secondary); }
.markdown-body :deep(code) { background: #f1f5f9; padding: 1px 6px; border-radius: 4px; font-family: Consolas, monospace; font-size: 12.5px; }
.markdown-body :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 12px; border-radius: 8px; overflow-x: auto; margin: 8px 0; }
.markdown-body :deep(pre code) { background: transparent; color: inherit; padding: 0; font-size: 12.5px; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 8px 0; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid var(--border); padding: 6px 10px; }
.markdown-body :deep(a) { color: var(--primary); }
</style>
