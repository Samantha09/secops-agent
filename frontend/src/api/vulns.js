import client from './client'

export const listVulns = (params) => client.get('/vulns', { params })
export const getVuln = (id) => client.get(`/vulns/${id}`)
export const updateVulnStatus = (id, status) => client.patch(`/vulns/${id}/status`, { status })
