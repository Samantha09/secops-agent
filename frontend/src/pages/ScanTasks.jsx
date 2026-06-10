import React, { useState, useEffect, useRef } from 'react'
import { Card, Button, Table, Tag, Progress, Space, Modal, Form, Select, message, Typography } from 'antd'
import { PlayCircleOutlined, FileTextOutlined } from '@ant-design/icons'
import client from '../api/client'

const { Paragraph } = Typography

const statusMap = {
  'PENDING': { text: '等待中', color: 'default' },
  'QUEUED': { text: '队列中', color: 'orange' },
  'RUNNING': { text: '运行中', color: 'blue' },
  'COMPLETED': { text: '完成', color: 'green' },
  'FAILED': { text: '失败', color: 'red' },
}

const stageMap = {
  'SUBDOMAIN_SCAN': '子域名发现',
  'PORT_SCAN': '端口扫描',
  'HTTP_PROBE': '存活探测',
  'VULN_SCAN': '漏洞扫描',
  'COMPLETED': '扫描完成',
  'FAILED': '执行失败',
  'AGENT_ANALYZING': 'Agent 分析中',
  'AGENT_THINKING': 'Agent 思考中',
  'AGENT_COMPLETE': '分析完成',
  'AGENT_ERROR': '分析失败',
}

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/scans`

export default function ScanTasks() {
  const [data, setData] = useState([])
  const [targets, setTargets] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [logOpen, setLogOpen] = useState(false)
  const [logRecord, setLogRecord] = useState(null)
  const [form] = Form.useForm()
  const wsRef = useRef(null)

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

  // WebSocket 实时进度
  useEffect(() => {
    const ws = new WebSocket(WS_URL)
    wsRef.current = ws

    ws.onopen = () => {
      console.log('扫描进度 WebSocket 已连接')
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        if (msg.type === 'SCAN_PROGRESS') {
          setData(prev => prev.map(t => {
            if (t.taskId === msg.taskId) {
              return {
                ...t,
                status: msg.status,
                progress: msg.progress,
                stage: msg.stage,
                stageMessage: msg.message,
              }
            }
            return t
          }))
        }
      } catch (e) {
        console.error('WebSocket 消息解析失败', e)
      }
    }

    ws.onerror = (err) => {
      console.error('扫描进度 WebSocket 错误', err)
    }

    ws.onclose = () => {
      console.log('扫描进度 WebSocket 已关闭')
    }

    return () => {
      ws.close()
    }
  }, [])

  // 降级轮询：当 WebSocket 断开时，每 5s 轮询一次
  useEffect(() => {
    const interval = setInterval(() => {
      const ws = wsRef.current
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        if (data.some(d => d.status === 'RUNNING' || d.status === 'QUEUED')) {
          fetchTasks()
        }
      }
    }, 5000)
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

  const handleViewLog = (record) => {
    setLogRecord(record)
    setLogOpen(true)
  }

  const columns = [
    { title: '任务ID', dataIndex: 'taskId', key: 'taskId' },
    { title: '目标', dataIndex: ['target', 'domain'], key: 'target' },
    { title: '扫描类型', dataIndex: 'scanType', key: 'scanType' },
    { title: '状态', dataIndex: 'status', key: 'status',
      render: (s, record) => (
        <Tag color={statusMap[s]?.color}>
          {statusMap[s]?.text || s}
          {record.stageMessage ? ` (${record.stageMessage})` : ''}
        </Tag>
      ) },
    { title: '进度', dataIndex: 'progress', key: 'progress',
      render: (p, record) => (
        <div>
          <Progress percent={p} size="small" />
          {record.stage && <div style={{ fontSize: 12, color: '#888' }}>{stageMap[record.stage] || record.stage}</div>}
        </div>
      ) },
    { title: '操作', key: 'action',
      render: (_, record) => (
        <Space>
          <Button type="link" icon={<FileTextOutlined />} onClick={() => handleViewLog(record)}>
            日志
          </Button>
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
              <Select.Option value="FULL">完整扫描（子域名 → 端口 → 存活 → 漏洞）</Select.Option>
              <Select.Option value="SUBDOMAIN">子域名发现</Select.Option>
              <Select.Option value="PORT">端口扫描</Select.Option>
              <Select.Option value="VULN">漏洞扫描</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>开始扫描</Button>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`扫描日志 - ${logRecord?.taskId || ''}`}
        open={logOpen}
        onCancel={() => setLogOpen(false)}
        footer={null}
        width={700}
      >
        {logRecord && (
          <div>
            <p><strong>状态:</strong> {statusMap[logRecord.status]?.text || logRecord.status}</p>
            <p><strong>目标:</strong> {logRecord.target?.domain}</p>
            {logRecord.errorMessage && (
              <p style={{ color: '#cf1322' }}><strong>错误:</strong> {logRecord.errorMessage}</p>
            )}
            <Paragraph
              copyable
              style={{
                background: '#f6f8fa',
                padding: 12,
                borderRadius: 6,
                maxHeight: 400,
                overflow: 'auto',
                fontFamily: 'monospace',
                fontSize: 12,
                whiteSpace: 'pre-wrap',
              }}
            >
              {logRecord.rawOutput || '暂无日志'}
            </Paragraph>
          </div>
        )}
      </Modal>
    </div>
  )
}
