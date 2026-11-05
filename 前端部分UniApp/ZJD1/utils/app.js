// app.js - 修复订单创建逻辑
import { chat_with_ai } from './agent.js'
import * as API from './api.js'
import { parse_ai_response } from './agent.js'

let messages = []
let agentContext = {
  userId: null,
  selectedRestaurantId: null,
  selectedRestaurantName: null,
  currentMenu: null,
  cart: [],
  currentOrderId: null,
  allRestaurants: []
}

const initUser = async () => {
  if (agentContext.userId) return
  
  try {
    const storedUserId = uni.getStorageSync('userId')
    if (storedUserId) {
      agentContext.userId = storedUserId
      return
    }
    
    const loginRes = await API.login('13800000000')
    agentContext.userId = loginRes.userId
    uni.setStorageSync('userId', loginRes.userId)
  } catch (e) {
    agentContext.userId = 'default_user_001'
  }
}

function extractDishesFromMessage(message, menu) {
  if (!menu || !Array.isArray(menu)) return []

  const dishes = []
  const lowerMessage = message.toLowerCase()

  for (const item of menu) {
    const dishName = item.name.toLowerCase()
    if (lowerMessage.includes(dishName) || lowerMessage.includes(dishName.replace(/\s+/g, ''))) {
      let quantity = 1
      const numberMatch = message.match(/(\d+)\s*份?/)
      if (numberMatch) quantity = parseInt(numberMatch[1])
      else if (message.includes('两') || message.includes('双')) quantity = 2

      dishes.push({
        dish_id: item.dishId,
        quantity
      })
      break
    }
  }

  return dishes
}

export async function run_agent(user_text, context = {}, on_token) {
  if (typeof on_token !== 'function') return

  let finished = false
  let round = 0

  await initUser()
  agentContext = { ...agentContext, ...context }

  messages.push({ role: 'user', content: user_text })

  while (!finished && round < 5) {
    round++

    try {
      const ai_response = await chat_with_ai(messages)
      const result = parse_ai_response(ai_response)

      if (result.type === 'function_call') {
        const { name, arguments: params } = result

        messages.push({
          role: 'assistant',
          content: `用户ID:${agentContext.userId}，操作:${name}`,
          function_call: { name, arguments: JSON.stringify(params) }
        })

        let tool_result = null

        switch (name) {
          case 'list_restaurants':
            tool_result = await API.list_restaurants()
            agentContext.allRestaurants = tool_result
            on_token({
              type: 'restaurant_list',
              data: tool_result.map(r => ({
                id: r.restaurantId,
                name: r.name,
                rating: r.rating,
                cuisine: r.cuisine || '美食',
                is_open: r.auditStatus === 'APPROVED'
              }))
            })
            break

          case 'list_dishes':
            if (params.restaurant_name && agentContext.allRestaurants.length > 0) {
              const matched = agentContext.allRestaurants.find(
                r => r.name.includes(params.restaurant_name)
              )
              if (matched) {
                agentContext.selectedRestaurantId = matched.restaurantId
                agentContext.selectedRestaurantName = matched.name
              }
            }

            if (!agentContext.selectedRestaurantId) {
              on_token({ type: 'text', content: '请先选一家餐厅～' })
              finished = true
              break
            }

            tool_result = await API.list_dishes(agentContext.selectedRestaurantId)
            agentContext.currentMenu = tool_result

            on_token({
              type: 'dish_list',
              data: tool_result.map(d => ({
                id: d.dishId,
                name: d.name,
                price: d.price,
                description: d.description || '美味佳肴'
              }))
            })
            break

          case 'create_order':
            if (!agentContext.selectedRestaurantId || !agentContext.userId) {
              on_token({ type: 'text', content: '请先选餐厅并登录～' })
              finished = true
              break
            }
            
            let dishes = params.dishes || []
            if (dishes.length === 0 && agentContext.currentMenu) {
              const lastUserMessage = messages.filter(m => m.role === 'user').pop()
              if (lastUserMessage) {
                dishes = extractDishesFromMessage(lastUserMessage.content, agentContext.currentMenu)
              }
            }
            
            if (dishes.length === 0) {
              on_token({ type: 'text', content: '请告诉我您想点什么菜品～' })
              finished = true
              break
            }
            
            // ✅ 完全匹配后端参数名
            const orderParams = {
              userId: agentContext.userId,
              restaurantId: agentContext.selectedRestaurantId,
              address: '北京市海淀区中关村大街1号',
              items: dishes.map(d => ({
                dishId: d.dish_id,
                quantity: d.quantity
              })),
              needPay: true
            }
            
            console.log('🔵 [App] 下单参数:', JSON.stringify(orderParams, null, 2))
            try {
              const createResult = await API.create_order(orderParams)
              agentContext.currentOrderId = createResult.orderId
              agentContext.currentOrderNo = createResult.orderNo
              
              on_token({
                type: 'order_success',
                content: `下单成功！订单号：${createResult.orderNo}`
              })
            } catch (error) {
              console.error('🔴 [App] 下单失败:', error)
              on_token({ type: 'text', content: '下单失败，请重试～' })
            }
            finished = true
            break
        }

        if (tool_result) {
          messages.push({
            role: 'function',
            name,
            content: JSON.stringify(tool_result)
          })
        }

        finished = true
      } else if (result.type === 'text') {
        messages.push({ role: 'assistant', content: result.content })
        on_token({ type: 'text', content: result.content })
        finished = true
      }
    } catch (error) {
      console.error('🔴 [App] 错误:', error)
      on_token({ type: 'text', content: '出错了，请重试～' })
      finished = true
    }
  }
}