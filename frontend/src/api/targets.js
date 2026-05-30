import client from './client'

export function listTargets() {
  return client.get('/targets')
}

export function createTarget(data) {
  return client.post('/targets', data)
}

export function verifyTarget(id) {
  return client.post(`/targets/${id}/verify`)
}

export function deleteTarget(id) {
  return client.delete(`/targets/${id}`)
}
