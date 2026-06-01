import React, { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import {
  Layout,
  Menu,
  Avatar,
  Badge,
  Space,
  Typography,
  Dropdown,
} from 'antd'
import {
  DashboardOutlined,
  GlobalOutlined,
  ScanOutlined,
  BugOutlined,
  FileTextOutlined,
  RobotOutlined,
  LogoutOutlined,
} from '@ant-design/icons'

const { Header, Sider } = Layout
const { Title } = Typography

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: '/targets', icon: <GlobalOutlined />, label: '目标管理' },
  { key: '/scans', icon: <ScanOutlined />, label: '扫描任务' },
  { key: '/vulns', icon: <BugOutlined />, label: '漏洞管理' },
  { key: '/tickets', icon: <FileTextOutlined />, label: '修复工单' },
  { key: '/agent', icon: <RobotOutlined />, label: 'Agent 助手' },
]

export default function AppLayout({ children }) {
  const [collapsed, setCollapsed] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { logout } = useAuth()

  const userMenuItems = [
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }
  ]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        theme="light"
        style={{
          boxShadow: '2px 0 8px rgba(0,0,0,0.05)',
        }}
      >
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <RobotOutlined style={{ fontSize: 24, color: '#1677ff' }} />
          {!collapsed && (
            <Title level={5} style={{ margin: 0, marginLeft: 8 }}>
              SecOps Agent
            </Title>
          )}
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            boxShadow: '0 1px 4px rgba(0,0,0,0.05)',
          }}
        >
          <Space size={16}>
            <Badge count={5} size="small">
              <BugOutlined style={{ fontSize: 18 }} />
            </Badge>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Avatar style={{ backgroundColor: '#1677ff', cursor: 'pointer' }}>S</Avatar>
            </Dropdown>
          </Space>
        </Header>
        {children}
      </Layout>
    </Layout>
  )
}
