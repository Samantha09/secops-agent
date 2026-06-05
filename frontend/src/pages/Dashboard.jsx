import React, { useEffect, useState } from 'react'
import { Row, Col, Card, Statistic, List, Tag } from 'antd'
import {
  GlobalOutlined,
  ScanOutlined,
  BugOutlined,
  FileTextOutlined,
} from '@ant-design/icons'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import client from '../api/client'

const severityColors = {
  CRITICAL: 'red',
  HIGH: 'orange',
  MEDIUM: 'yellow',
  LOW: 'blue',
  INFO: 'default',
}

export default function Dashboard() {
  const [stats, setStats] = useState({
    targetCount: 0,
    scanTaskCount: 0,
    vulnCount: 0,
    ticketCount: 0,
    dailyVulnTrend: [],
    recentVulns: [],
  })
  const [loading, setLoading] = useState(false)

  const fetchStats = async () => {
    setLoading(true)
    try {
      const res = await client.get('/stats')
      if (res.code === 200) {
        setStats(res.data)
      }
    } catch (err) {
      console.error('加载统计数据失败', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchStats()
  }, [])

  return (
    <div>
      <h2>安全仪表盘</h2>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="监控目标"
              value={stats.targetCount}
              prefix={<GlobalOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="扫描任务"
              value={stats.scanTaskCount}
              prefix={<ScanOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="高危漏洞"
              value={stats.vulnCount}
              valueStyle={{ color: '#cf1322' }}
              prefix={<BugOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="待修复工单"
              value={stats.ticketCount}
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={12}>
          <Card title="风险趋势" loading={loading}>
            <ResponsiveContainer width="100%" height={300}>
              <BarChart data={stats.dailyVulnTrend}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="date" />
                <YAxis allowDecimals={false} />
                <Tooltip />
                <Bar dataKey="count" fill="#1677ff" name="漏洞数" />
              </BarChart>
            </ResponsiveContainer>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="最新漏洞" loading={loading}>
            <List
              dataSource={stats.recentVulns}
              renderItem={(item) => (
                <List.Item>
                  <List.Item.Meta
                    title={item.name}
                    description={
                      <span>
                        {item.target}{' '}
                        <Tag color={severityColors[item.severity]}>{item.severity}</Tag>
                      </span>
                    }
                  />
                  <div>{item.foundAt ? new Date(item.foundAt).toLocaleDateString() : '-'}</div>
                </List.Item>
              )}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}
