import http from './http'

export const listConversations = () => http.get('/agent/conversations')
export const createConversation = (data) => http.post('/agent/conversations', data)
export const getConversation = (id) => http.get(`/agent/conversations/${id}`)
export const renameConversation = (id, title) => http.patch(`/agent/conversations/${id}/title`, { title })
export const deleteConversation = (id) => http.delete(`/agent/conversations/${id}`)
export const listMessages = (id) => http.get(`/agent/conversations/${id}/messages`)
