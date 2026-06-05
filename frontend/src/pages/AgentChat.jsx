import React, { useEffect, useRef, useState } from 'react'
import { Card, Input, Button, List, Avatar, Typography, Space, Tag } from 'antd'
import { RobotOutlined, UserOutlined, SendOutlined } from '@ant-design/icons'

const { Text } = Typography

export default function AgentChat() {
  const [messages, setMessages] = useState([
    { role: 'assistant', content: '你好，我是 SecOps Agent。我可以帮你分析扫描结果、生成修复建议或回答安全问题。' },
  ])
  const [input, setInput] = useState('')
  const [thinking, setThinking] = useState(false)
  const [thinkSteps, setThinkSteps] = useState([])
  const wsRef = useRef(null)

  useEffect(() => {
    const ws = new WebSocket('ws://localhost:8081/ws/agent')
    wsRef.current = ws

    ws.onopen = () => {
      console.log('Agent WebSocket 已连接')
    }

    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data)
      switch (msg.type) {
        case 'connected':
          console.log(msg.data.message)
          break
        case 'think':
          setThinkSteps((prev) => [...prev, { type: 'think', content: msg.data.content }])
          break
        case 'action':
          setThinkSteps((prev) => [...prev, { type: 'action', tool: msg.data.tool, params: msg.data.params }])
          break
        case 'observe':
          setThinkSteps((prev) => [...prev, { type: 'observe', content: msg.data.content }])
          break
        case 'complete':
          setMessages((prev) => [
            ...prev,
            { role: 'assistant', content: msg.data.content },
          ])
          setThinking(false)
          setThinkSteps([])
          break
        case 'error':
          setMessages((prev) => [
            ...prev,
            { role: 'assistant', content: `【错误】${msg.data.message}` },
          ])
          setThinking(false)
          setThinkSteps([])
          break
        default:
          break
      }
    }

    ws.onerror = (err) => {
      console.error('WebSocket 错误', err)
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '【连接错误】无法连接到 Agent 服务，请确认后端已启动。' },
      ])
      setThinking(false)
    }

    ws.onclose = () => {
      console.log('Agent WebSocket 已关闭')
    }

    return () => {
      ws.close()
    }
  }, [])

  const send = () => {
    if (!input.trim()) return
    setMessages((prev) => [...prev, { role: 'user', content: input }])
    setInput('')
    setThinking(true)
    setThinkSteps([])

    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ content: input }))
    } else {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '【连接断开】WebSocket 未连接，请刷新页面重试。' },
      ])
      setThinking(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 112px)' }}>
      <h2>Agent 安全助手</h2>
      <Card style={{ flex: 1, marginTop: 16, overflow: 'auto' }}>
        <List
          dataSource={messages}
          renderItem={(msg) => (
            <List.Item
              style={{
                justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
                border: 'none',
                flexDirection: 'column',
                alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start',
              }}
            >
              <Space>
                {msg.role === 'assistant' && (
                  <Avatar style={{ backgroundColor: '#1677ff' }} icon={<RobotOutlined />} />
                )}
                <div
                  style={{
                    padding: '8px 16px',
                    borderRadius: 12,
                    background: msg.role === 'user' ? '#1677ff' : '#f0f0f0',
                    color: msg.role === 'user' ? '#fff' : '#333',
                    maxWidth: 600,
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  <Text style={{ color: 'inherit' }}>{msg.content}</Text>
                </div>
                {msg.role === 'user' && (
                  <Avatar style={{ backgroundColor: '#52c41a' }} icon={<UserOutlined />} />
                )}
              </Space>
            </List.Item>
          )}
        />
        {thinking && (
          <div style={{ marginLeft: 40, marginTop: 8 }}>
            {thinkSteps.map((step, idx) => (
              <div key={idx} style={{ marginBottom: 4, fontSize: 12, color: '#888' }}>
                {step.type === 'think' && <Text type="secondary">💭 {step.content}</Text>}
                {step.type === 'action' && (
                  <Tag size="small" color="blue">
                    🔧 {step.tool}
                  </Tag>
                )}
                {step.type === 'observe' && <Text type="secondary">👁 {step.content}</Text>}
              </div>
            ))}
            {thinkSteps.length === 0 && <Text type="secondary">Agent 思考中...</Text>}
          </div>
        )}
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
        <Button type="primary" icon={<SendOutlined />} onClick={send} loading={thinking}>
          发送
        </Button>
      </div>
    </div>
  )
}
