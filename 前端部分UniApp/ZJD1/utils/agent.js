// agent.js - 显示请求体和AI响应，不显示Prompt
const API_KEY = 'sk-ws-H.RXMPYPH.oGiw.MEUCIQCSnc-WYeNK7tpeuAJ-jPEanZ6LMpdLnKf5aKIgBL5COwIgSlq_avNLCfKc1a2uGKgBCdQGGEYTH5hfcXFyV12NEo8'
const APP_ID = '4db85e29fa974c2db2149e0b66db5c09'

let sessionId = null

function build_prompt(messages) {
  return messages
    .map(m => {
      if (m.role === 'user') return `用户：${m.content}`
      if (m.role === 'assistant') return `助手：${m.content}`
      if (m.role === 'function') return `系统结果：${m.content}`
      return ''
    })
    .filter(Boolean)
    .join('\n')
}

export async function chat_with_ai(messages) {
  const prompt = build_prompt(messages)

  const body = {
    input: { prompt }
  }

  if (sessionId) {
    body.parameters = { session_id: sessionId }
  }

  // ✅ 显示请求体（不含Prompt字符串）
  console.log('🔵 [Agent] 请求体:', JSON.stringify(body, null, 2))

  return new Promise((resolve, reject) => {
    uni.request({
      url: `https://dashscope.aliyuncs.com/api/v1/apps/${APP_ID}/completion`,
      method: 'POST',
      header: {
        'Authorization': `Bearer ${API_KEY}`,
        'Content-Type': 'application/json'
      },
      data: body,
      success: (res) => {
        if (res.statusCode === 200) {
          sessionId = res.data?.output?.session_id || sessionId
          resolve(res.data)
        } else {
          reject(new Error(`百炼 ${res.statusCode}: ${JSON.stringify(res.data)}`))
        }
      },
      fail: (err) => {
        reject(err)
      }
    })
  })
}

export function parse_ai_response(ai_response) {
  // ✅ 显示AI原始响应
  console.log('🟢 [Agent] AI 原始响应:', JSON.stringify(ai_response, null, 2))

  const { output } = ai_response || {}
  if (!output) return { type: 'unknown' }

  if (output.function_call) {
    return {
      type: 'function_call',
      name: output.function_call.name,
      arguments: JSON.parse(output.function_call.arguments || '{}')
    }
  }

  if (output.text) {
    try {
      const p = JSON.parse(output.text)
      if (p.name) {
        return {
          type: 'function_call',
          name: p.name,
          arguments: p.arguments || {}
        }
      }
    } catch {}
    return { type: 'text', content: output.text }
  }

  return { type: 'unknown' }
}