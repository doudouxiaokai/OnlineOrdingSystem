// agent-ws.js —— Agent 流式 WebSocket 封装
// 后端 /ws/agent 通道：提交 {userId, sessionId, text}，流式接收 plan/step/done/error 事件。
import { WS_URL } from './request.js'

/**
 * 发送一次 Agent 对话请求，通过回调流式接收执行过程。
 * @param {Object} options
 * @param {string} options.text      用户输入
 * @param {string} [options.userId]  用户 ID
 * @param {string} [options.sessionId] 会话 ID
 * @param {Function} options.onPlan   (data) 计划就绪：data.plan.steps 为 DAG 结构
 * @param {Function} options.onStep   (data) 步骤状态：data.stepId/tool/status/output
 * @param {Function} options.onDone   (data) 最终结果：data.content/runId/tokenUsed
 * @param {Function} options.onError  (data) 错误：data.message
 * @param {Function} [options.onClose]
 */
export function sendAgentMessage(options) {
  const socket = uni.connectSocket({
    url: WS_URL,
    complete: () => {}
  })

  socket.onOpen(() => {
    socket.send({
      data: JSON.stringify({
        userId: options.userId || null,
        sessionId: options.sessionId || null,
        text: options.text
      })
    })
  })

  socket.onMessage((res) => {
    let data
    try {
      data = JSON.parse(res.data)
    } catch (e) {
      return
    }
    switch (data.type) {
      case 'plan':
        options.onPlan && options.onPlan(data)
        break
      case 'step':
        options.onStep && options.onStep(data)
        break
      case 'done':
        options.onDone && options.onDone(data)
        socket.close({})
        break
      case 'error':
        options.onError && options.onError(data)
        break
      default:
        break
    }
  })

  socket.onError(() => {
    options.onError && options.onError({ message: 'WebSocket 连接失败，请稍后重试' })
  })

  socket.onClose(() => {
    options.onClose && options.onClose()
  })

  return socket
}
