import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message } from 'antd'
import { useAuth } from '../context/AuthContext'
import client from '../api/client'

export default function Register() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const { login } = useAuth()

  const onFinish = async (values) => {
    setLoading(true)
    try {
      const res = await client.post('/auth/register', values)
      if (res.code === 200) {
        login(res.data)
        message.success('注册成功')
        navigate('/dashboard')
      } else {
        message.error(res.msg)
      }
    } catch (e) {
      message.error(e.message || '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card title="SecOps Agent 注册" style={{ width: 360 }}>
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: true, type: 'email', message: '请输入有效邮箱' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>注册</Button>
          </Form.Item>
          <Button type="link" onClick={() => navigate('/login')} block>已有账号？去登录</Button>
        </Form>
      </Card>
    </div>
  )
}
