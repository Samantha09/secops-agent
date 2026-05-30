import React, { useState } from 'react'
import { Card, Input, Button, List, Avatar, Typography, Space } from 'antd'
import { RobotOutlined, UserOutlined, SendOutlined } from '@ant-design/icons'

const { Text } = Typography

export default function AgentChat() {
  const [messages, setMessages] = useState([
    { role: 'assistant', content: '你好，我是 SecOps Agent。我可以帮你分析扫描结果、生成修复建议或回答安全问题。' },
  ])
  const [input, setInput] = useState('')

  const send = () => {
    if (!input.trim()) return
    setMessages((prev) => [...prev, { role: 'user', content: input }])
    setInput('')
    // TODO: call backend Agent API
    setTimeout(() => {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '收到你的问题，我正在分析相关漏洞数据...（Agent 回复占位）' },
      ])
    }, 800)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 112px)' }}>
      <h2>Agent 安全助手</h2>
      <Card style={{ flex: 1, marginTop: 16, overflow: 'auto' }}>
        <List
          dataSource={messages}
          renderItem={(msg) => (
            <List.Item style={{ justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start', border: 'none' }}>
              <Space>
                {msg.role === 'assistant' && <Avatar style={{ backgroundColor: '#1677ff' }} icon={<RobotOutlined />} />}
                <div
                  style={{
                    padding: '8px 16px',
                    borderRadius: 12,
                    background: msg.role === 'user' ? '#1677ff' : '#f0f0f0',
                    color: msg.role === 'user' ? '#fff' : '#333',
                    maxWidth: 600,
                  }}
                >
                  <Text style={{ color: 'inherit' }}>{msg.content}</Text>
                </div>
                {msg.role === 'user' && <Avatar style={{ backgroundColor: '#52c41a' }} icon={<UserOutlined />} />}
              </Space>
            </List.Item>
          )}
        />
      </Card>
      <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="询问漏洞详情、修复建议..."
          autoSize={{ minRows: 1, maxRows: 4 }}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              send()
            }
          }}
        />
        <Button type="primary" icon={<SendOutlined />} onClick={send}>
          发送
        </Button>
      </div>
    </div>
  )
}
