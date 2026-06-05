import React, { useState, useEffect } from 'react'
import { Card, Button, Table, Tag, Progress, Space, Modal, Form, Select, message } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import client from '../api/client'

const statusMap = {
  'PENDING': { text: '等待中', color: 'default' },
  'RUNNING': { text: '运行中', color: 'blue' },
  'COMPLETED': { text: '完成', color: 'green' },
  'FAILED': { text: '失败', color: 'red' },
}

export default function ScanTasks() {
  const [data, setData] = useState([])
  const [targets, setTargets] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm()

  const fetchTasks = async () => {
    setLoading(true)
    try {
      const res = await client.get('/scans')
      if (res.code === 200) {
        setData(res.data.map(t => ({ ...t, key: t.id })))
      }
    } catch (err) {
      message.error(err.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  const fetchTargets = async () => {
    try {
      const res = await client.get('/targets')
      if (res.code === 200) {
        setTargets(res.data.filter(t => t.verified))
      }
    } catch {}
  }

  useEffect(() => {
    fetchTasks()
    fetchTargets()
  }, [])

  // Poll progress for running tasks every 3s
  useEffect(() => {
    const interval = setInterval(() => {
      if (data.some(d => d.status === 'RUNNING')) {
        fetchTasks()
      }
    }, 3000)
    return () => clearInterval(interval)
  }, [data])

  const handleLaunch = async (values) => {
    try {
      const res = await client.post('/scans', values)
      if (res.code === 200) {
        message.success('扫描任务已创建')
        setModalOpen(false)
        form.resetFields()
        fetchTasks()
      } else {
        message.error(res.msg || '创建失败')
      }
    } catch (err) {
      message.error(err.message || '创建失败')
    }
  }

  const columns = [
    { title: '任务ID', dataIndex: 'taskId', key: 'taskId' },
    { title: '目标', dataIndex: ['target', 'domain'], key: 'target' },
    { title: '扫描类型', dataIndex: 'scanType', key: 'scanType' },
    { title: '状态', dataIndex: 'status', key: 'status',
      render: (s) => <Tag color={statusMap[s]?.color}>{statusMap[s]?.text || s}</Tag> },
    { title: '进度', dataIndex: 'progress', key: 'progress',
      render: (p) => <Progress percent={p} size="small" /> },
    { title: '操作', key: 'action',
      render: (_, record) => (
        <Space>
          <a>日志</a>
          <a href={`/vulns?scanId=${record.id}`}>结果</a>
        </Space>
      ) },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>扫描任务</h2>
        <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => setModalOpen(true)}>发起扫描</Button>
      </div>
      <Card>
        <Table columns={columns} dataSource={data} loading={loading} scroll={{ x: 'max-content' }} />
      </Card>

      <Modal title="发起扫描" open={modalOpen} onCancel={() => setModalOpen(false)} footer={null}>
        <Form form={form} onFinish={handleLaunch} layout="vertical">
          <Form.Item name="targetId" label="目标" rules={[{ required: true, message: '请选择目标' }]}>
            <Select placeholder="选择已验证的目标">
              {targets.map(t => <Select.Option key={t.id} value={t.id}>{t.domain}</Select.Option>)}
            </Select>
          </Form.Item>
          <Form.Item name="scanType" label="扫描类型" initialValue="FULL" rules={[{ required: true }]}>
            <Select>
              <Select.Option value="FULL">全量漏洞扫描</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>开始扫描</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
