<template>
    <view class="container">
        <view class="status-card">
            <view class="status-text">
                订单状态：<text class="highlight">{{ formatStatus(order.status) }}</text>
            </view>
			<view class="tips" v-if="order.status === 'UNPAID'">⏳ 订单正在支付</view>
			<view class="tips" v-if="order.status === 'CANCELLED'">❌ 订单已取消</view>
            <view class="tips" v-if="order.status === 'PREPARING'">🍲 商家做饭中</view>
            <view class="tips" v-if="order.status === 'DELIVERING'">🛵 骑手配送中</view>
            <view class="tips" v-if="order.status === 'COMPLETED'">✅ 订单已送达</view>
        </view>

		<view class="info-card" v-if="order.items && order.items.length > 0">
            <view class="info-title">🧾 订单明细</view>
            <view style="display: flex; justify-content: space-between; font-size: 14px; margin-bottom: 8px; border-bottom: 1px dashed #eee; padding-bottom: 8px;" 
                  v-for="item in order.items" :key="item.itemId">
                <text>{{ item.dishName || '菜品' }}</text>
                <text>x {{ item.quantity }}</text>
            </view>
            <view style="text-align: right; font-weight: bold; margin-top: 10px;">
                合计：￥{{ order.totalAmount }}
            </view>
        </view>

        <view class="rider-card" v-if="order.riderName">
            <view class="rider-info">
                <view class="name">骑手：{{ order.riderName }}</view>
                <view class="phone">电话：{{ order.riderPhone }}</view>
            </view>
            <button class="btn-call">📞 联系骑手</button>
        </view>

        <view class="admin-action-box" v-if="order.status === 'PREPARING' || order.status === 'DELIVERING'">
            <button class="btn-next" @click="advanceOrderStatus">
                {{ order.status === 'PREPARING' ? '【模拟】商家出餐' : '【模拟】确认送达' }}
            </button>
        </view>
		
		<view class="admin-action-box" v-if="order.status === 'UNPAID'">
			<button class="btn-next" @click="goToPay">
				{{'【模拟】去支付'}}
			</button>
		</view>

        <view class="review-box" v-if="order.status === 'COMPLETED'">
            <view class="info-title">🌟 评价订单</view>
            <view class="rating-stars">
                <text v-for="star in 5" :key="star" @click="score = star" class="star-icon">
                    {{ score >= star ? '★' : '☆' }}
                </text>
            </view>
            <textarea class="comment-area" placeholder="写下评价..." v-model="comment" />
            <button class="btn-submit-review" @click="submitOrderReview">提交评价</button>
        </view>

        <button class="btn-home-back" @click="handleBack">返回</button>
    </view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            orderId: '',
            order: {},
            score: 5,
            comment: '',
            source: '' // 新增：记录来源页面
        }
    },
    onLoad(options) {
        this.orderId = options.orderId;
        this.source = options.source || ''; // 新增：接收来源参数
        this.fetchOrderDetails();
    },
    methods: {
        goToPay() {
            if (!this.order.orderId || !this.order.totalAmount) {
                uni.showToast({ title: '订单信息异常', icon: 'none' });
                return;
            }
            uni.navigateTo({
                url: `/pages/order/pay?orderId=${this.order.orderId}&totalAmount=${this.order.totalAmount}`,
                fail: (err) => {
                    console.error('跳转支付页失败:', err);
                    uni.showToast({ title: '跳转失败，请重试', icon: 'none' });
                }
            });
        },
        // 新增：处理返回逻辑
        handleBack() {
            if (this.source === 'settle') {
                // 从下单页面来的，返回到店铺页面
                const restaurantId = uni.getStorageSync('tempRestaurantId');
                if (restaurantId) {
                    uni.redirectTo({
                        url: `/pages/menu/menu?restaurantId=${restaurantId}`
                    });
                } else {
                    uni.navigateBack();
                }
            } else {
                // 默认行为：返回上一页（历史订单页面）
                uni.navigateBack();
            }
        },
        
        async fetchOrderDetails() {
            try {
                const res = await request({
                    url: '/api/orders/track',
                    method: 'GET',
                    data: { orderId: this.orderId }
                });
                this.order = res || {};
            } catch (e) {
                console.error(e);
            }
        },
        
        async advanceOrderStatus() {
            try {
                await request({
                    url: '/api/orders/advance',
                    method: 'POST',
                    data: { orderId: this.orderId }
                });
                uni.showToast({ title: '状态更新成功' });
                this.fetchOrderDetails();
            } catch (e) {
                uni.showToast({ title: '请求失败', icon: 'none' });
            }
        },
        
        async submitOrderReview() {
            if (!this.comment.trim()) {
                uni.showToast({ title: '内容不能为空', icon: 'none' });
                return;
            }
            try {
                await request({
                    url: '/api/reviews',
                    method: 'POST',
                    data: {
                        orderId: this.orderId,
                        restaurantId: this.order.restaurantId,
                        userId: this.order.userId,
                        username: uni.getStorageSync('username') || '匿名用户',
                        rating: this.score,
                        comment: this.comment
                    }
                });
                uni.showToast({ title: '感谢您的好评！' });
                setTimeout(() => this.handleBack(), 1200);
            } catch (e) {
                console.error(e);
            }
        },
        
        backToHome() {
            uni.reLaunch({ url: '/pages/index/index' });
        },
        
        formatStatus(status) {
            const dict = {
                PREPARING: '商家做饭中',
                DELIVERING: '骑手配送中',
                COMPLETED: '已送达完成',
				CANCELLED: '订单已取消',
				UNPAID: '订单支付中',
            };
            return dict[status] || status;
        }
    }
}
</script>

<style>
.container { padding: 20px; background: #f7f7f7; min-height: 100vh; }
.status-card, .info-card, .rider-card, .review-box { background: #fff; padding: 15px; border-radius: 12px; margin-bottom: 12px; }
.highlight { color: #ff5722; font-weight: bold; }
.info-title { font-weight: bold; margin-bottom: 8px; font-size: 15px; }
.btn-next { background: #007aff; color: #fff; font-weight: bold; border-radius: 6px; margin-top: 10px; }
.rating-stars { display: flex; font-size: 30px; color: #ffca28; margin: 10px 0; }
.comment-area { border: 1px solid #ddd; width: 100%; height: 80px; padding: 8px; border-radius: 6px; box-sizing: border-box; }
.btn-submit-review { background: #e91e63; color: #fff; margin-top: 10px; border-radius: 6px; }
.btn-home-back { margin-top: 20px; font-size: 14px; }
</style>