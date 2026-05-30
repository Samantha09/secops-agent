import React from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Layout } from 'antd'
import AppLayout from './components/layout/AppLayout'
import Dashboard from './pages/Dashboard'
import Targets from './pages/Targets'
import ScanTasks from './pages/ScanTasks'
import Vulnerabilities from './pages/Vulnerabilities'
import Tickets from './pages/Tickets'
import AgentChat from './pages/AgentChat'

const { Content } = Layout

function App() {
  return (
    <AppLayout>
      <Content style={{ margin: '24px 16px', padding: 24, background: '#fff', borderRadius: 8 }}>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/targets" element={<Targets />} />
          <Route path="/scans" element={<ScanTasks />} />
          <Route path="/vulns" element={<Vulnerabilities />} />
          <Route path="/tickets" element={<Tickets />} />
          <Route path="/agent" element={<AgentChat />} />
        </Routes>
      </Content>
    </AppLayout>
  )
}

export default App
