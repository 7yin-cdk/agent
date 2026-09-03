/*
 * 对话端点采用 POST + SSE（EventSource 不支持 POST），故用 fetch 流式读取，
 * 自行解析 text/event-stream 帧。事件序列：meta → status → delta* → done | error。
 */
const EVENT_TYPES = ['meta', 'status', 'delta', 'done', 'error']

export function streamChat({ query, conversationId, token, handlers }) {
  const controller = new AbortController()
  let settled = false

  const fail = (err) => {
    settled = true
    if (handlers.onError) handlers.onError(err instanceof Error ? err : new Error(String(err)))
  }

  const dispatchFrame = (part) => {
    if (!part.trim()) return
    let eventName = 'message'
    const dataLines = []
    for (const line of part.split(/\r?\n/)) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    if (!EVENT_TYPES.includes(eventName)) return
    const data = JSON.parse(dataLines.join('\n') || '{}')
    if (eventName === 'done' || eventName === 'error') settled = true
    if (eventName === 'delta' && handlers.onDelta) handlers.onDelta(data.content || '')
    else if (eventName === 'meta' && handlers.onMeta) handlers.onMeta(data)
    else if (eventName === 'status' && handlers.onStatus) handlers.onStatus(data)
    else if (eventName === 'done' && handlers.onDone) handlers.onDone(data)
    else if (eventName === 'error') fail(new Error(data.message || '对话出错'))
  }

  const run = async () => {
    let response
    try {
      response = await fetch('/agent/chat/reactive/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify({ query, conversationId: conversationId || undefined }),
        signal: controller.signal
      })
    } catch (err) {
      if (!settled && !controller.signal.aborted) fail(err)
      return
    }
    if (!response.ok || !response.body) {
      fail(new Error(`HTTP ${response.status}`))
      return
    }
    if (handlers.onOpen) handlers.onOpen()
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        /* 帧以空行分隔；末段可能是半个帧，留到 buffer 里 */
        const parts = buffer.split(/\r?\n\r?\n/)
        buffer = parts.pop()
        for (const part of parts) dispatchFrame(part)
      }
    } catch (err) {
      if (!settled && !controller.signal.aborted) fail(err)
      return
    }
    if (buffer.trim() && !settled) dispatchFrame(buffer)
    if (!settled && handlers.onClose) handlers.onClose()
  }

  run()
  return {
    abort() {
      controller.abort()
    }
  }
}
