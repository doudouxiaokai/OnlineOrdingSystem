<template>
    <view class="container">
        <view class="header-title">历史订单</view>

        <view class="no-data" v-if="orderList.length === 0">
            暂无历史订单，快去下单吧 🍔
        </view>

        <view
            class="order-card"
            v-for="order in orderList"
            :key="order.orderId"
            @click="goToTrack(order.orderId, order.restaurantId)"
        >
            <view class="card-header">
                <text class="shop-name">订单号：{{ order.orderNo }}</text>
                <text class="status-tag">{{ formatStatus(order.status) }}</text>
            </view>

			<view class="card-body">
				<view style="display: flex; flex-direction: column; margin-top: 10px; gap: 5px;">
					<text class="time">下单时间：{{ formatTime(order.createdAt) }}</text>
					<text class="price">实付：￥{{ order.totalAmount }}</text>
				</view>
			</view>

			<view class="card-footer">
                <button class="btn-action-mini btn-delete" @click.stop="handleDeleteOrder(order.orderId)">删除订单</button>
                <button class="btn-action-mini" @click.stop="goToTrack(order.orderId, order.restaurantId)">查看轨迹</button>
            </view>
        </view>
    </view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            orderList: []
        };
    },

    onShow() {
        this.getOrders();
    },

    methods: {
		async handleDeleteOrder(orderId) {
			uni.showModal({
				title: '提示',
				content: '确定要删除这条历史订单吗？',
				success: async (res) => {
					if (res.confirm) {
						try {
							await request({
								url: `/api/orders/${orderId}`,
								method: 'DELETE'
							});
							uni.showToast({ title: '删除成功' });
							this.getOrders();
						} catch (e) {
							uni.showToast({ title: '删除失败', icon: 'none' });
						}
					}
				}
			});
		},
        async getOrders() {
            const userId = uni.getStorageSync('userId');
            if (!userId) {
                uni.showToast({ title: '请先登录', icon: 'none' });
                return;
            }

            try {
                const data = await request({
                    url: '/api/orders/list',
                    method: 'GET',
                    data: { userId }
                });
                this.orderList = data || [];
            } catch (e) {
                console.error('获取订单失败', e);
            }
        },

        goToTrack(orderId, restaurantId) {
            uni.navigateTo({
                url: `/pages/order/track?orderId=${orderId}&restaurantId=${restaurantId}`
            });
        },

        formatStatus(status) {
            const dict = {
                PREPARING: '做饭中',
                DELIVERING: '配送中',
                COMPLETED: '已送达',
                DELIVERY_EXCEPTION: '配送异常',
                CANCELLED: '已取消',
				UNPAID: '等待支付',
            };
            return dict[status] || status;
        },

        formatTime(time) {
            if (!time) return '刚刚';
            const t = time.replace('T', ' ').substring(0, 19);
            return t;
        }
    }
};
</script>

<style>
.container {
    padding: 15px;
    background: #f7f7f7;
    min-height: 100vh;
    box-sizing: border-box;
}

.header-title {
    font-size: 20px;
    font-weight: bold;
    margin-bottom: 15px;
}

.no-data {
    text-align: center;
    color: #999;
    margin-top: 80px;
    font-size: 14px;
}

.order-card {
    background: #fff;
    padding: 15px;
    border-radius: 10px;
    margin-bottom: 12px;
    box-shadow: 0 1px 6px rgba(0, 0, 0, 0.04);
}

.card-header {
    display: flex;
    justify-content: space-between;
    border-bottom: 1px solid #f5f5f5;
    padding-bottom: 8px;
    margin-bottom: 10px;
}

.shop-name {
    font-weight: bold;
    font-size: 14px;
    color: #333;
}

.status-tag {
    color: #ff5722;
    font-size: 13px;
    font-weight: bold;
}

.card-body {
    display: flex;
    justify-content: space-between;
    font-size: 13px;
    color: #666;
}

.price {
    font-weight: bold;
    color: #111;
    font-size: 14px;
}

.card-footer {
    display: flex;
    justify-content: flex-end;
    margin-top: 10px;
    padding-top: 8px;
    border-top: 1px solid #f5f5f5;
}

.btn-action-mini {
    margin: 0;
    font-size: 12px;
    background: #007aff;
    color: #fff;
    height: 26px;
    line-height: 26px;
    border-radius: 13px;
    padding: 0 12px;
}
.btn-delete { background: #f44336; margin-right: 10px; }
</style>