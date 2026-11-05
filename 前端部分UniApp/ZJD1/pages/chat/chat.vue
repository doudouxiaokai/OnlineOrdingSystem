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
        
        <view v-if="isWaiting" class="message ai">
          <view class="bubble loading">正在思考...</view>
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
import { run_agent } from '@/utils/app.js'

export default {
  data() {
    return {
      inputText: '',
      messages: [
        { role: 'ai', type: 'text', content: '你好！我是你的点餐助手。你想吃什么？' }
      ],
      isWaiting: false,
      scrollTop: 0,
      context: {}
    }
  },
  methods: {
    async sendMessage() {
      if (!this.inputText.trim() || this.isWaiting) return
      
      const userText = this.inputText
      this.messages.push({ role: 'user', type: 'text', content: userText })
      this.inputText = ''
      this.isWaiting = true
      
      try {
        await run_agent(
          userText, 
          this.context,
          (response) => {
            this.handleAiResponse(response)
          }
        )
      } catch (error) {
        console.error('请求失败:', error)
        this.messages.push({ 
          role: 'ai', 
          type: 'text', 
          content: '网络开小差了，请稍后再试。' 
        })
      } finally {
        this.isWaiting = false
        this.scrollToBottom()
      }
    },
    
    handleAiResponse(response) {
      switch (response.type) {
        case 'restaurant_list':
          this.messages.push({
            role: 'ai',
            type: 'restaurant_list',
            data: response.data
          })
          break
          
        case 'dish_list':
          this.messages.push({
            role: 'ai',
            type: 'dish_list',
            data: response.data
          })
          break
          
        case 'order_success':
          this.messages.push({
            role: 'ai',
            type: 'order_success',
            content: response.content
          })
          break
          
        case 'payment_success':
          this.messages.push({
            role: 'ai',
            type: 'payment_success',
            content: response.content
          })
          break
          
        case 'order_update':
          this.messages.push({
            role: 'ai',
            type: 'order_update',
            content: response.content
          })
          break
          
        case 'text':
        default:
          this.messages.push({
            role: 'ai',
            type: 'text',
            content: response.content || '抱歉，我有点困惑。'
          })
          break
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