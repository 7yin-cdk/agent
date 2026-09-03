import http from './http'

export const pageMemories = (params) => http.get('/agent/memories', { params })
export const searchMemories = (params) => http.get('/agent/memories/search', { params })
export const addMemory = (data) => http.post('/agent/memories', data)
export const updateMemory = (id, data) => http.patch(`/agent/memories/${id}`, data)
export const deleteMemory = (id) => http.delete(`/agent/memories/${id}`)
export const clearMemories = (category) =>
  http.delete('/agent/memories', { params: category ? { category } : {} })
