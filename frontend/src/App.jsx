import React from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Layout } from 'antd'
import { useAuth } from './context/AuthContext'
import AppLayout from './components/layout/AppLayout'
import Login from './pages/Login'
import Register from './pages/Register'
import Dashboard from './pages/Dashboard'
import Targets from './pages/Targets'
import ScanTasks from './pages/ScanTasks'
import Vulnerabilities from './pages/Vulnerabilities'
import Tickets from './pages/Tickets'
import AgentChat from './pages/AgentChat'

const { Content } = Layout

function ProtectedRoute({ children }) {
  const { isLoggedIn } = useAuth()
  return isLoggedIn ? children : <Navigate to="/login" replace />
}

function App() {
  const { isLoggedIn } = useAuth()

  return (
    <Routes>
      <Route path="/login" element={isLoggedIn ? <Navigate to="/dashboard" /> : <Login />} />
      <Route path="/register" element={isLoggedIn ? <Navigate to="/dashboard" /> : <Register />} />
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={
        <ProtectedRoute>
          <AppLayout>
            <Content style={{ margin: '16px 8px', padding: 16, background: '#fff', borderRadius: 8, overflow: 'auto' }}>
              <Routes>
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/targets" element={<Targets />} />
                <Route path="/scans" element={<ScanTasks />} />
                <Route path="/vulns" element={<Vulnerabilities />} />
                <Route path="/tickets" element={<Tickets />} />
                <Route path="/agent" element={<AgentChat />} />
              </Routes>
            </Content>
          </AppLayout>
        </ProtectedRoute>
      } />
    </Routes>
  )
}

export default App
