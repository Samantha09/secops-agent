import React, { useEffect, useState } from 'react'
import {
  Card,
  Table,
  Tag,
  Space,
  Button,
  Modal,
  Descriptions,
  message,
  Popconfirm,
} from 'antd'
import { EyeOutlined, FileTextOutlined } from '@ant-design/icons'
import { listVulns, updateVulnStatus } from '../api/vulns'
import { createTicket } from '../api/tickets'

const severityColors = {
  CRITICAL: 'red',
  HIGH: 'orange',
  MEDIUM: 'yellow',
  LOW: 'blue',
  INFO: 'default',
}

const statusMap = {
  OPEN: '未修复',
  FIXED: '已修复',
  FALSE_POSITIVE: '误报',
}

export default function Vulnerabilities() {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(false)
  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState(null)

  const fetchData = async () => {
    setLoading(true)
    try {
      const res = await listVulns()
      if (res.code === 200) {
        setData(res.data.map((item) => ({ ...item, key: item.id })))
      } else {
        message.error(res.msg || '加载失败')
      }
    } catch (err) {
      message.error(err.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchData()
  }, [])

  const handleCreateTicket = async (vuln) => {
    try {
      const res = await createTicket(vuln.id)
      if (res.code === 200) {
        message.success('工单创建成功')
      } else {
        message.error(res.msg || '创建失败')
      }
    } catch (err) {
      message.error(err.message || '创建失败')
    }
  }

  const handleStatusChange = async (id, status) => {
    try {
      const res = await updateVulnStatus(id, status)
      if (res.code === 200) {
        message.success('状态更新成功')
        fetchData()
      } else {
        message.error(res.msg || '更新失败')
      }
    } catch (err) {
      message.error(err.message || '更新失败')
    }
  }

  const columns = [
    { title: '漏洞', dataIndex: 'name', key: 'name' },
    { title: '目标', dataIndex: 'target', key: 'target' },
    {
      title: '等级',
      dataIndex: 'severity',
      key: 'severity',
      render: (s) => <Tag color={severityColors[s]}>{s}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s) => <Tag>{statusMap[s] || s}</Tag>,
    },
    {
      title: '发现时间',
      dataIndex: 'foundAt',
      key: 'foundAt',
      render: (v) => (v ? new Date(v).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      render: (_, record) => (
        <Space>
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={() => {
              setDetail(record)
              setDetailOpen(true)
            }}
          >
            详情
          </Button>
          <Button
            type="link"
            icon={<FileTextOutlined />}
            onClick={() => handleCreateTicket(record)}
          >
            创建工单
          </Button>
          {record.status === 'OPEN' && (
            <>
              <Popconfirm
                title="标记为误报"
                onConfirm={() => handleStatusChange(record.id, 'FALSE_POSITIVE')}
              >
                <Button type="link" danger>
                  误报
                </Button>
              </Popconfirm>
              <Popconfirm
                title="标记为已修复"
                onConfirm={() => handleStatusChange(record.id, 'FIXED')}
              >
                <Button type="link">已修复</Button>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>漏洞管理</h2>
      <Card>
        <Table columns={columns} dataSource={data} loading={loading} scroll={{ x: 'max-content' }} />
      </Card>

      <Modal
        title="漏洞详情"
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={600}
      >
        {detail && (
          <Descriptions column={1} bordered>
            <Descriptions.Item label="漏洞名称">{detail.name}</Descriptions.Item>
            <Descriptions.Item label="目标">{detail.target}</Descriptions.Item>
            <Descriptions.Item label="等级">
              <Tag color={severityColors[detail.severity]}>{detail.severity}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag>{statusMap[detail.status] || detail.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="扫描器">{detail.scanner}</Descriptions.Item>
            <Descriptions.Item label="匹配内容">{detail.matched}</Descriptions.Item>
            <Descriptions.Item label="描述">{detail.description}</Descriptions.Item>
            <Descriptions.Item label="发现时间">
              {detail.foundAt ? new Date(detail.foundAt).toLocaleString() : '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  )
}
