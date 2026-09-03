import http from './http'

export const pageDocuments = (params) => http.get('/agent/kb/documents', { params })
export const uploadDocument = (file) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/agent/kb/documents', form, { timeout: 120000 })
}
export const deleteDocument = (id) => http.delete(`/agent/kb/documents/${id}`)
export const pageChunks = (fileId, params) => http.get(`/agent/kb/documents/${fileId}/chunks`, { params })
export const retrieve = (data) => http.post('/agent/kb/retrieve', data)
