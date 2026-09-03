import http from './http'

export const runHealthCheck = () => http.post('/healthcheck/run')
export const getHealthRecords = (limit = 50) => http.get('/healthcheck/records', { params: { limit } })
