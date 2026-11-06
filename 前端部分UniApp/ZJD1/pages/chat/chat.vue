<template>
  <view class="container">

    <!-- 聊天区域 -->
    <scroll-view scroll-y class="chat-box" :scroll-top="scrollTop">
      <view class="message-list">
        <view v-for="(msg, index) in messages" :key="index" class="message" :class="msg.role">
          <!-- 普通文本消息 -->
          <view v-if="msg.type === 'text'" class="bubble">{{ msg.content }}</view>
          
          <!-- 餐厅列表卡片 -->
          <view v-else-if="msg.type === 'restaurant_list'" class="bubble restaurant-card">
            <view class="card-title">🍽️ 为您找到以下餐厅：</view>
            <view v-for="item in msg.data" :key="item.id" class="restaurant-item">
              <view class="item-header">
                <text class="name">{{ item.name }}</text>
                <text class="status" :class="{ closed: !item.is_open }">{{ item.is_open ? '营业中' : '休息中' }}</text>
              </view>
              <view class="info">⭐ 评分：{{ item.rating }}</view>
              <view class="info">🍜 菜系：{{ item.cuisine }}</view>
            </view>
          </view>
          
          <!-- 菜品列表卡片 -->
          <view v-else-if="msg.type === 'dish_list'" class="bubble dish-card">
            <view class="card-title">🍜 餐厅菜品列表：</view>
            <view v-for="item in msg.data" :key="item.id" class="dish-item">
              <view class="dish-info">
                <text class="dish-name">{{ item.name }}</text>
                <text class="dish-price">¥{{ item.price }}</text>
              </view>
              <view class="dish-desc">{{ item.description }}</view>
            </view>
          </view>
          
          <!-- 订单成功提示 -->
          <view v-else-if="msg.type === 'order_success'" class="bubble order-success">
            <view class="success-icon">✅</view>
            <view class="success-text">{{ msg.content }}</view>
          </view>
          
          <!-- 支付成功提示 -->
          <view v-else-if="msg.type === 'payment_success'" class="bubble payment-success">
            <view class="success-icon">💳</view>
            <view class="success-text">{{ msg.content }}</view>
          </view>
          
          <!-- 订单状态更新 -->
          <view v-else-if="msg.type === 'order_update'" class="bubble order-update">
            <view class="update-icon">🚚</view>
            <view class="update-text">{{ msg.content }}</view>
          </view>
        </view>
        
        <!-- 等待提示 -->
        <view v-if="isWaiting" class="message ai">
          <view class="bubble loading">正在思考...</view>
        </view>

        <!-- 执行轨迹面板（实时展示计划 DAG 与步骤状态） -->
        <view v-if="tracePanel && tracePanel.steps.length" class="trace-panel">
          <view class="trace-header">
            <text class="trace-title">⚙️ 执行轨迹</text>
            <text class="trace-status" :class="{ running: tracePanel.status === 'running' }">
              {{ tracePanel.status === 'running' ? '运行中…' : '已完成' }}
            </text>
          </view>
          <view v-for="s in tracePanel.steps" :key="s.stepId" class="trace-step">
            <view class="trace-icon" :class="'st-' + (s.status || 'PENDING').toLowerCase()">{{ statusIcon(s.status) }}</view>
            <view class="trace-body">
              <text class="trace-tool">{{ s.stepId }} · {{ s.tool }}</text>
              <text v-if="s.dependsOn && s.dependsOn.length" class="trace-dep">依赖：{{ s.dependsOn.join(', ') }}</text>
              <text v-if="s.output" class="trace-output">{{ formatOutput(s.output) }}</text>
            </view>
          </view>
        </view>
      </view>
    </scroll-view>

    <!-- 底部输入栏 -->
    <view class="input-bar">
      <input 
        class="input" 
        v-model="inputText" 
        placeholder="请输入您想吃的菜品..." 
        @confirm="sendMessage"
        :disabled="isWaiting"
      />
      <button class="btn" @click="sendMessage" :disabled="isWaiting">发送</button>
    </view>
  </view>
</template>

<script>
import { sendAgentMessage } from '@/utils/agent-ws.js'

export default {
  data() {
    return {
      inputText: '',
      messages: [
        { role: 'ai', type: 'text', content: '你好！我是你的点餐助手。你想吃什么？' }
      ],
      isWaiting: false,
      scrollTop: 0,
      context: {},
      // 执行轨迹：{ runId, status: 'running'|'done', steps: [{stepId, tool, status, dependsOn, expect, output}] }
      tracePanel: null
    }
  },
  methods: {
    sendMessage() {
      if (!this.inputText.trim() || this.isWaiting) return
      
      const userText = this.inputText
      this.messages.push({ role: 'user', type: 'text', content: userText })
      this.inputText = ''
      this.isWaiting = true
      // 每次对话重置轨迹面板
      this.tracePanel = { runId: null, status: 'running', steps: [] }
      this.scrollToBottom()

      const userId = uni.getStorageSync('userId') || 'default_user_001'

      sendAgentMessage({
        text: userText,
        userId: userId,
        sessionId: this.context.sessionId || null,
        onPlan: (data) => {
          this.tracePanel.runId = data.runId
          this.tracePanel.steps = (data.plan && data.plan.steps || []).map(s => ({
            stepId: s.stepId,
            tool: s.tool,
            status: s.status || 'PENDING',
            dependsOn: s.dependsOn || [],
            expect: s.expect || '',
            output: s.output || null
          }))
          this.scrollToBottom()
        },
        onStep: (data) => {
          const step = this.tracePanel.steps.find(x => x.stepId === data.stepId)
          if (step) {
            step.status = data.status
            if (data.output) {
              this.$set(step, 'output', data.output)
            }
          }
          this.scrollToBottom()
        },
        onDone: (data) => {
          this.messages.push({ role: 'ai', type: 'text', content: data.content || '已完成。' })
          if (this.tracePanel) this.tracePanel.status = 'done'
          this.isWaiting = false
          this.scrollToBottom()
        },
        onError: (data) => {
          this.messages.push({ role: 'ai', type: 'text', content: data.message || '连接失败，请稍后再试。' })
          if (this.tracePanel) this.tracePanel.status = 'done'
          this.isWaiting = false
          this.scrollToBottom()
        }
      })
    },
    
    statusIcon(status) {
      switch (status) {
        case 'RUNNING': return '⏳'
        case 'DONE': return '✅'
        case 'FAILED': return '❌'
        case 'SKIPPED': return '⏭️'
        default: return '○'
      }
    },
    
    formatOutput(output) {
      try {
        const s = JSON.stringify(output)
        return s.length > 100 ? s.slice(0, 100) + '…' : s
      } catch (e) {
        return String(output)
      }
    },
    
    scrollToBottom() {
      this.$nextTick(() => {
        this.scrollTop = Date.now()
      })
    }
  }
}
</script>

<style>
.container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
}

.header {
  padding: 24rpx 0;
  text-align: center;
  background: #fff;
  border-bottom: 1rpx solid #eee;
  flex-shrink: 0;
}

.title {
  font-size: 34rpx;
  font-weight: bold;
}

.chat-box {
  flex: 1;
  padding: 20rpx;
  box-sizing: border-box;
  overflow-y: auto;
}

.message-list {
  display: flex;
  flex-direction: column;
}

.message {
  margin-bottom: 24rpx;
  display: flex;
}

.user {
  justify-content: flex-end;
}

.ai {
  justify-content: flex-start;
}

.bubble {
  max-width: 72%;
  padding: 18rpx 24rpx;
  border-radius: 20rpx;
  word-break: break-word;
  font-size: 28rpx;
  background: #fff;
  color: #333;
}

.user .bubble {
  background: #007aff;
  color: #fff;
  border-bottom-right-radius: 6rpx;
}

.loading {
  color: #999;
  font-style: italic;
}

/* 执行轨迹面板 */
.trace-panel {
  background: #1f2430;
  border-radius: 16rpx;
  padding: 20rpx;
  margin-bottom: 24rpx;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}

.trace-title {
  color: #fff;
  font-size: 28rpx;
  font-weight: bold;
}

.trace-status {
  color: #7ee787;
  font-size: 22rpx;
}

.trace-status.running {
  color: #ffd166;
}

.trace-step {
  display: flex;
  align-items: flex-start;
  padding: 12rpx 0;
  border-bottom: 1rpx solid rgba(255, 255, 255, 0.06);
}

.trace-step:last-child {
  border-bottom: none;
}

.trace-icon {
  font-size: 28rpx;
  margin-right: 16rpx;
  width: 36rpx;
  text-align: center;
}

.st-running {
  color: #ffd166;
}

.st-done {
  color: #7ee787;
}

.st-failed {
  color: #ff7b72;
}

.st-pending {
  color: #8b949e;
}

.trace-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.trace-tool {
  color: #e6edf3;
  font-size: 26rpx;
  font-weight: bold;
}

.trace-dep {
  color: #8b949e;
  font-size: 22rpx;
  margin-top: 4rpx;
}

.trace-output {
  color: #a5b4c4;
  font-size: 20rpx;
  margin-top: 6rpx;
  word-break: break-all;
}

/* 餐厅列表卡片样式 */
.restaurant-card {
  background: #ffffff;
  width: 100%;
  padding: 20rpx;
}

.card-title {
  font-weight: bold;
  margin-bottom: 16rpx;
  color: #333;
  font-size: 30rpx;
}

.restaurant-item {
  background: #f9f9f9;
  padding: 16rpx;
  border-radius: 10rpx;
  margin-bottom: 10rpx;
  border-left: 4rpx solid #007aff;
}

.item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8rpx;
}

.name {
  font-weight: bold;
  font-size: 30rpx;
  color: #333;
}

.status {
  font-size: 24rpx;
  padding: 4rpx 10rpx;
  border-radius: 8rpx;
  background: #e6f7ff;
  color: #1890ff;
}

.status.closed {
  background: #fff2e8;
  color: #fa541c;
}

.info {
  font-size: 26rpx;
  color: #666;
  margin-top: 4rpx;
}

/* 菜品列表卡片样式 */
.dish-card {
  background: #ffffff;
  width: 100%;
  padding: 20rpx;
}

.dish-item {
  background: #f9f9f9;
  padding: 16rpx;
  border-radius: 10rpx;
  margin-bottom: 10rpx;
  border-left: 4rpx solid #52c41a;
}

.dish-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8rpx;
}

.dish-name {
  font-weight: bold;
  font-size: 30rpx;
  color: #333;
}

.dish-price {
  font-weight: bold;
  font-size: 30rpx;
  color: #f5222d;
}

.dish-desc {
  font-size: 26rpx;
  color: #666;
}

/* 成功提示样式 */
.order-success, .payment-success {
  background: #f6ffed;
  border: 1rpx solid #b7eb8f;
  display: flex;
  align-items: center;
  padding: 20rpx;
}

.success-icon, .update-icon {
  font-size: 40rpx;
  margin-right: 16rpx;
}

.success-text, .update-text {
  font-size: 28rpx;
  color: #333;
}

.payment-success {
  background: #e6f7ff;
  border: 1rpx solid #91d5ff;
}

/* 订单更新样式 */
.order-update {
  background: #fffbe6;
  border: 1rpx solid #ffe58f;
  display: flex;
  align-items: center;
  padding: 20rpx;
}

.input-bar {
  display: flex;
  padding: 16rpx 20rpx;
  background: #fff;
  border-top: 1rpx solid #eee;
  flex-shrink: 0;
}

.input {
  flex: 1;
  height: 72rpx;
  background: #f2f2f2;
  border-radius: 36rpx;
  padding: 0 24rpx;
  font-size: 28rpx;
}

.btn {
  margin-left: 16rpx;
  background: #007aff;
  color: #fff;
  font-size: 28rpx;
  border-radius: 36rpx;
  height: 72rpx;
  line-height: 72rpx;
  padding: 0 32rpx;
  border: none;
}

.btn[disabled] {
  background: #ccc;
}
</style>
