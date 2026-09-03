import { defineStore } from 'pinia'
import * as convApi from '../api/conversation'
import * as obsApi from '../api/observability'
import { getToken } from '../api/http'
import { streamChat } from '../utils/sse'
import { useAuthStore } from './auth'

const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversations: [],
    activeId: null,
    messages: [],
    streaming: false,
    sseHandle: null
  }),
  getters: {
    activeConversation: (s) => s.conversations.find((c) => c.conversationId === s.activeId) || null
  },
  actions: {
    async fetchConversations() {
      this.conversations = await convApi.listConversations()
      if (!this.activeId && this.conversations.length > 0) {
        this.activeId = this.conversations[0].conversationId
        await this.loadMessages()
      }
    },

    async newConversation() {
      this.stop()
      const res = await convApi.createConversation({})
      this.conversations.unshift(res)
      this.activeId = res.conversationId
      this.messages = []
    },

    async selectConversation(id) {
      this.stop()
      this.activeId = id
      await this.loadMessages()
    },

    async loadMessages() {
      if (!this.activeId) return
      const list = await convApi.listMessages(this.activeId)
      this.messages = list.map((m, i) => ({
        id: `${m.messageOrder}-${i}`,
        role: m.role,
        content: m.content,
        createdAt: m.createdAt,
        intentType: null,
        trace: null
      }))
    },

    async renameConversation(id, title) {
      if (!title.trim()) return
      const res = await convApi.renameConversation(id, title.trim())
      const idx = this.conversations.findIndex((c) => c.conversationId === id)
      if (idx >= 0) this.conversations[idx] = res
    },

    async removeConversation(id) {
      this.stop()
      await convApi.deleteConversation(id)
      this.conversations = this.conversations.filter((c) => c.conversationId !== id)
      if (this.activeId === id) {
        this.activeId = this.conversations[0]?.conversationId || null
        this.messages = this.activeId ? await convApi.listMessages(this.activeId).then((l) => l.map((m, i) => ({ id: `${m.messageOrder}-${i}`, role: m.role, content: m.content, createdAt: m.createdAt, intentType: null, trace: null }))) : []
      }
    },

    async ensureConversation(query) {
      if (!this.activeId) {
        const res = await convApi.createConversation({})
        this.conversations.unshift(res)
        this.activeId = res.conversationId
      }
      if (this.activeConversation?.title === '新会话') {
        const title = query.trim().slice(0, 20)
        try {
          await this.renameConversation(this.activeId, title)
        } catch (e) {
          /* 标题更新失败不阻断对话 */
        }
      }
      return this.activeId
    },

    async send(rawQuery) {
      const query = rawQuery.trim()
      if (!query || this.streaming) return
      const conversationId = await this.ensureConversation(query)
      this.messages.push({ id: uid(), role: 'user', content: query, createdAt: new Date().toISOString() })
      const assistant = { id: uid(), role: 'assistant', content: '', createdAt: new Date().toISOString(), intentType: null, error: null, trace: null }
      this.messages.push(assistant)
      this.streaming = true

      const token = getToken()
      const updateLast = (fn) => {
        const last = this.messages[this.messages.length - 1]
        if (last && last.id === assistant.id) fn(last)
      }

      this.sseHandle = streamChat({
        query,
        conversationId,
        token,
        handlers: {
          onOpen: () => updateLast((m) => { m.streaming = true }),
          onStatus: (data) => updateLast((m) => { m.intentType = data.intentType }),
          onDelta: (text) => updateLast((m) => { m.content += text }),
          onDone: (data) => {
            updateLast((m) => {
              m.content = data.answer || m.content
              m.streaming = false
            })
            this.streaming = false
            this.tryAttachTrace(conversationId, 0)
          },
          onError: (err) => {
            updateLast((m) => {
              m.error = err.message
              m.streaming = false
            })
            this.streaming = false
          },
          onClose: () => {
            this.streaming = false
            updateLast((m) => { m.streaming = false })
          }
        }
      })
    },

    stop() {
      if (this.sseHandle) {
        this.sseHandle.abort()
        this.sseHandle = null
      }
      this.streaming = false
      const last = this.messages[this.messages.length - 1]
      if (last?.role === 'assistant') last.streaming = false
    },

    /* done 后从 observability 取本条对话最新 trace，补充工具/LLM 解释面板（失败轻量重试一次） */
    async tryAttachTrace(conversationId, attempt) {
      try {
        const traces = await obsApi.getTraces()
        const match = traces
          .filter((t) => t.conversationId === conversationId)
          .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))[0]
        if (!match) throw new Error('no trace yet')
        const detail = await obsApi.getTraceDetail(match.traceId)
        const last = this.messages[this.messages.length - 1]
        if (last?.role === 'assistant') last.trace = detail
      } catch (e) {
        if (attempt < 1) setTimeout(() => this.tryAttachTrace(conversationId, attempt + 1), 600)
      }
    }
  }
})
