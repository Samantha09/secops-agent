import React from 'react'
import { Card, Table, Tag, Space } from 'antd'

const columns = [
  { title: '工单号', dataIndex: 'id', key: 'id' },
  { title: '标题', dataIndex: 'title', key: 'title' },
  { title: '优先级', dataIndex: 'priority', key: 'priority', render: (p) => <Tag color={p === '高' ? 'red' : p === '中' ? 'orange' : 'blue'}>{p}</Tag> },
  { title: '负责人', dataIndex: 'assignee', key: 'assignee' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '操作', key: 'action', render: () => <Space><a>处理</a><a>关闭</a></Space> },
]

const data = [
  { key: '1', id: 'TKT-001', title: '修复 Actuator 未授权访问', priority: '高', assignee: '张三', status: '处理中' },
]

export default function Tickets() {
  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>修复工单</h2>
      <Card>
        <Table columns={columns} dataSource={data} />
      </Card>
    </div>
  )
}
