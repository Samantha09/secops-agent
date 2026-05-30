import React from 'react'
import { Card, Button, Table, Tag, Space } from 'antd'
import { PlusOutlined } from '@ant-design/icons'

const columns = [
  { title: '域名', dataIndex: 'domain', key: 'domain' },
  { title: '验证状态', dataIndex: 'verified', key: 'verified', render: (v) => v ? <Tag color="green">已验证</Tag> : <Tag color="orange">待验证</Tag> },
  { title: '子域名', dataIndex: 'subdomains', key: 'subdomains' },
  { title: '开放端口', dataIndex: 'ports', key: 'ports' },
  { title: '最后扫描', dataIndex: 'lastScan', key: 'lastScan' },
  { title: '操作', key: 'action', render: () => <Space><a>扫描</a><a>详情</a></Space> },
]

const data = [
  { key: '1', domain: 'example.com', verified: true, subdomains: 15, ports: 4, lastScan: '2025-05-26 14:00' },
]

export default function Targets() {
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>目标管理</h2>
        <Button type="primary" icon={<PlusOutlined />}>添加目标</Button>
      </div>
      <Card>
        <Table columns={columns} dataSource={data} />
      </Card>
    </div>
  )
}
