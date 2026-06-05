import client from './client'

export const listTickets = () => client.get('/tickets')
export const createTicket = (vulnerabilityId) => client.post('/tickets', { vulnerabilityId })
export const updateTicket = (id, data) => client.patch(`/tickets/${id}`, data)
export const deleteTicket = (id) => client.delete(`/tickets/${id}`)
