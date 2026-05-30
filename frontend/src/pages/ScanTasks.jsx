import React from 'react'
import { Card, Button, Table, Tag, Progress, Space } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'

const columns = [
  { title: '任务ID', dataIndex: 'id', key: 'id' },
  { title: '目标', dataIndex: 'target', key: 'target' },
  { title: '扫描类型', dataIndex: 'type', key: 'type' },
  { title: '状态', dataIndex: 'status', key: 'status', render: (s) => <Tag color={s === '运行中' ? 'blue' : s === '完成' ? 'green' : 'default'}>{s}</Tag> },
  { title: '进度', dataIndex: 'progress', key: 'progress', render: (p) => <Progress percent={p} size="small" /> },
  { title: '操作', key: 'action', render: () => <Space><a>日志</a><a>结果</a></Space> },
]

const data = [
  { key: '1', id: 'SCAN-20250527-001', target: 'example.com', type: '全量漏洞扫描', status: '运行中', progress: 65 },
]

export default function ScanTasks() {
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>扫描任务</h2>
        <Button type="primary" icon={<PlayCircleOutlined />}>发起扫描</Button>
      </div>
      <Card>
        <Table columns={columns} dataSource={data} />
      </Card>
    </div>
  )
}
