import React from 'react'
import { Card, Table, Tag, Space, Badge } from 'antd'

const severityColors = { critical: 'red', high: 'orange', medium: 'yellow', low: 'blue', info: 'default' }

const columns = [
  { title: '漏洞', dataIndex: 'name', key: 'name' },
  { title: '目标', dataIndex: 'target', key: 'target' },
  { title: '等级', dataIndex: 'severity', key: 'severity', render: (s) => <Badge color={severityColors[s]} text={s.toUpperCase()} /> },
  { title: '状态', dataIndex: 'status', key: 'status', render: (s) => <Tag>{s}</Tag> },
  { title: '发现时间', dataIndex: 'foundAt', key: 'foundAt' },
  { title: '操作', key: 'action', render: () => <Space><a>详情</a><a>创建工单</a></Space> },
]

const data = [
  { key: '1', name: 'Spring Boot Actuator 未授权', target: 'api.example.com', severity: 'high', status: '未修复', foundAt: '2025-05-26' },
]

export default function Vulnerabilities() {
  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>漏洞管理</h2>
      <Card>
        <Table columns={columns} dataSource={data} />
      </Card>
    </div>
  )
}
