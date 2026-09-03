import http from './http'

export const getStats = () => http.get('/agent/observability/stats')
export const getTraces = () => http.get('/agent/observability/traces')
export const getTraceDetail = (traceId) => http.get(`/agent/observability/traces/${traceId}`)
