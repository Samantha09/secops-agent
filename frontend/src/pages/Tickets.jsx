import React, { useEffect, useState } from 'react'
import { Card, Table, Tag, Space, Button, message, Popconfirm, Modal, Form, Input, Select } from 'antd'
import { listTickets, updateTicket, deleteTicket } from '../api/tickets'

const priorityColors = {
  CRITICAL: 'red',
  HIGH: 'orange',
  MEDIUM: 'yellow',
  LOW: 'blue',
  INFO: 'default',
}

const statusMap = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  CLOSED: '已关闭',
}

export default function Tickets() {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editRecord, setEditRecord] = useState(null)
  const [form] = Form.useForm()

  const fetchData = async () => {
    setLoading(true)
    try {
      const res = await listTickets()
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

  const handleStatusChange = async (record, nextStatus) => {
    try {
      const res = await updateTicket(record.id, { status: nextStatus })
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

  const handleDelete = async (id) => {
    try {
      const res = await deleteTicket(id)
      if (res.code === 200) {
        message.success('删除成功')
        fetchData()
      } else {
        message.error(res.msg || '删除失败')
      }
    } catch (err) {
      message.error(err.message || '删除失败')
    }
  }

  const handleEdit = (record) => {
    setEditRecord(record)
    form.setFieldsValue({
      assignee: record.assignee || '',
      status: record.status,
    })
    setEditOpen(true)
  }

  const handleSave = async (values) => {
    try {
      const res = await updateTicket(editRecord.id, values)
      if (res.code === 200) {
        message.success('保存成功')
        setEditOpen(false)
        fetchData()
      } else {
        message.error(res.msg || '保存失败')
      }
    } catch (err) {
      message.error(err.message || '保存失败')
    }
  }

  const columns = [
    { title: '工单号', dataIndex: 'id', key: 'id' },
    { title: '标题', dataIndex: 'title', key: 'title' },
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      render: (p) => <Tag color={priorityColors[p]}>{p}</Tag>,
    },
    { title: '负责人', dataIndex: 'assignee', key: 'assignee', render: (v) => v || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s) => <Tag>{statusMap[s] || s}</Tag>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v) => (v ? new Date(v).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      render: (_, record) => (
        <Space>
          {record.status === 'OPEN' && (
            <Button type="link" onClick={() => handleStatusChange(record, 'IN_PROGRESS')}>
              处理
            </Button>
          )}
          {record.status === 'IN_PROGRESS' && (
            <Button type="link" onClick={() => handleStatusChange(record, 'CLOSED')}>
              关闭
            </Button>
          )}
          <Button type="link" onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Popconfirm title="确认删除" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>修复工单</h2>
      <Card>
        <Table columns={columns} dataSource={data} loading={loading} />
      </Card>

      <Modal title="编辑工单" open={editOpen} onCancel={() => setEditOpen(false)} onOk={() => form.submit()}>
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="assignee" label="负责人">
            <Input placeholder="输入负责人姓名" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select>
              <Select.Option value="OPEN">待处理</Select.Option>
              <Select.Option value="IN_PROGRESS">处理中</Select.Option>
              <Select.Option value="CLOSED">已关闭</Select.Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
