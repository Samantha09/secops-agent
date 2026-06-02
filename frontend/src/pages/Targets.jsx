import React, { useEffect, useState } from 'react'
import {
  Card,
  Button,
  Table,
  Tag,
  Space,
  Modal,
  Form,
  Input,
  Select,
  message,
  Popconfirm,
  Typography,
  Descriptions,
} from 'antd'
import { PlusOutlined, CheckCircleOutlined, DeleteOutlined } from '@ant-design/icons'
import { listTargets, createTarget, verifyTarget, deleteTarget } from '../api/targets'

const { Text, Paragraph } = Typography

const typeMap = {
  DOMAIN: { text: '域名', color: 'blue' },
  IP: { text: 'IP', color: 'purple' },
}

const columns = (onVerify, onDelete) => [
  {
    title: '目标',
    dataIndex: 'domain',
    key: 'domain',
  },
  {
    title: '类型',
    dataIndex: 'type',
    key: 'type',
    render: (t) => <Tag color={typeMap[t]?.color}>{typeMap[t]?.text || t}</Tag>,
  },
  {
    title: '验证状态',
    dataIndex: 'verified',
    key: 'verified',
    render: (v) =>
      v ? (
        <Tag color="green">已验证</Tag>
      ) : (
        <Tag color="orange">待验证</Tag>
      ),
  },
  { title: '子域名', dataIndex: 'subdomains', key: 'subdomains' },
  { title: '开放端口', dataIndex: 'ports', key: 'ports' },
  {
    title: '最后扫描',
    dataIndex: 'lastScanAt',
    key: 'lastScanAt',
    render: (v) => v || '-',
  },
  {
    title: '操作',
    key: 'action',
    render: (_, record) => (
      <Space>
        {!record.verified && record.type === 'DOMAIN' && (
          <Button
            type="link"
            icon={<CheckCircleOutlined />}
            onClick={() => onVerify(record.id)}
          >
            验证
          </Button>
        )}
        <Popconfirm
          title="确认删除"
          description={`删除目标 ${record.domain}？`}
          onConfirm={() => onDelete(record.id)}
          okText="删除"
          cancelText="取消"
        >
          <Button type="link" danger icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      </Space>
    ),
  },
]

export default function Targets() {
  const [targets, setTargets] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [detailOpen, setDetailOpen] = useState(false)
  const [detailTarget, setDetailTarget] = useState(null)
  const [form] = Form.useForm()
  const [targetType, setTargetType] = useState('DOMAIN')

  const fetchTargets = async () => {
    setLoading(true)
    try {
      const res = await listTargets()
      if (res.code === 200) {
        setTargets(res.data.map((t) => ({ key: t.id, ...t })))
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
    fetchTargets()
  }, [])

  const handleCreate = async (values) => {
    try {
      const res = await createTarget(values)
      if (res.code === 200) {
        message.success('添加成功')
        setModalOpen(false)
        form.resetFields()
        setTargetType('DOMAIN')
        setDetailTarget(res.data)
        setDetailOpen(true)
        fetchTargets()
      } else {
        message.error(res.msg || '添加失败')
      }
    } catch (err) {
      message.error(err.message || '添加失败')
    }
  }

  const handleVerify = async (id) => {
    try {
      const res = await verifyTarget(id)
      if (res.code === 200) {
        if (res.data.verified) {
          message.success('DNS TXT 验证通过')
        } else {
          message.warning('未检测到正确的 DNS TXT 记录，请检查域名 DNS 配置')
        }
        fetchTargets()
      } else {
        message.error(res.msg || '验证失败')
      }
    } catch (err) {
      message.error(err.message || '验证失败')
    }
  }

  const handleDelete = async (id) => {
    try {
      const res = await deleteTarget(id)
      if (res.code === 200) {
        message.success('删除成功')
        fetchTargets()
      } else {
        message.error(res.msg || '删除失败')
      }
    } catch (err) {
      message.error(err.message || '删除失败')
    }
  }

  const validateDomainOrIp = (_, value) => {
    const type = form.getFieldValue('type') || 'DOMAIN'
    if (!value) return Promise.resolve()

    const ipRegex = /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/
    const domainRegex = /^(?!-)[A-Za-z0-9-]{1,63}(?<!-)\.(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\.[A-Za-z0-9-]{1,63})*$/

    if (type === 'IP') {
      if (!ipRegex.test(value)) {
        return Promise.reject(new Error('请输入有效的 IP 地址'))
      }
    } else {
      if (!domainRegex.test(value)) {
        return Promise.reject(new Error('域名格式不正确'))
      }
    }
    return Promise.resolve()
  }

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
        }}
      >
        <h2>目标管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          添加目标
        </Button>
      </div>

      <Card>
        <Table
          columns={columns(handleVerify, handleDelete)}
          dataSource={targets}
          loading={loading}
          onRow={(record) => ({
            onClick: () => {
              setDetailTarget(record)
              setDetailOpen(true)
            },
            style: { cursor: 'pointer' },
          })}
        />
      </Card>

      <Modal
        title="添加目标"
        open={modalOpen}
        onCancel={() => {
          setModalOpen(false)
          form.resetFields()
          setTargetType('DOMAIN')
        }}
        onOk={() => form.submit()}
        okText="添加"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" onFinish={handleCreate}>
          <Form.Item
            name="type"
            label="目标类型"
            initialValue="DOMAIN"
            rules={[{ required: true, message: '请选择目标类型' }]}
          >
            <Select onChange={(v) => setTargetType(v)}>
              <Select.Option value="DOMAIN">域名</Select.Option>
              <Select.Option value="IP">IP 地址</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item
            name="domain"
            label={targetType === 'IP' ? 'IP 地址' : '域名'}
            rules={[
              { required: true, message: targetType === 'IP' ? '请输入 IP 地址' : '请输入域名' },
              { validator: validateDomainOrIp },
            ]}
          >
            <Input placeholder={targetType === 'IP' ? '127.0.0.1' : 'example.com'} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="目标详情"
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
      >
        {detailTarget && (
          <Descriptions column={1} bordered>
            <Descriptions.Item label="目标">{detailTarget.domain}</Descriptions.Item>
            <Descriptions.Item label="类型">
              <Tag color={typeMap[detailTarget.type]?.color}>
                {typeMap[detailTarget.type]?.text || detailTarget.type}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="验证状态">
              {detailTarget.verified ? (
                <Tag color="green">已验证</Tag>
              ) : (
                <Tag color="orange">待验证</Tag>
              )}
            </Descriptions.Item>
            {!detailTarget.verified && detailTarget.type === 'DOMAIN' && (
              <Descriptions.Item label="TXT 验证记录">
                <Paragraph copyable>{detailTarget.txtRecord}</Paragraph>
                <Text type="secondary">
                  请在域名 DNS 中添加 TXT 记录，值为上方内容，然后点击验证。
                </Text>
              </Descriptions.Item>
            )}
            <Descriptions.Item label="子域名">{detailTarget.subdomains}</Descriptions.Item>
            <Descriptions.Item label="开放端口">{detailTarget.ports}</Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {detailTarget.createdAt}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  )
}
