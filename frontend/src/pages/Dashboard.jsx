import React from 'react'
import { Row, Col, Card, Statistic } from 'antd'
import {
  GlobalOutlined,
  ScanOutlined,
  BugOutlined,
  FileTextOutlined,
} from '@ant-design/icons'

export default function Dashboard() {
  return (
    <div>
      <h2>安全仪表盘</h2>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="监控目标"
              value={12}
              prefix={<GlobalOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="扫描任务"
              value={48}
              prefix={<ScanOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="高危漏洞"
              value={7}
              valueStyle={{ color: '#cf1322' }}
              prefix={<BugOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="待修复工单"
              value={15}
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={12}>
          <Card title="风险趋势">
            <div style={{ height: 300, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
              图表占位（ECharts 接入后替换）
            </div>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="最新漏洞">
            <div style={{ height: 300, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
              漏洞列表占位
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
