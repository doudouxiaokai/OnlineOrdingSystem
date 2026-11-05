<template>
    <view class="container">
        <view class="section-title">点餐菜单</view>
        <view class="dish-list">
            <view class="dish-item" v-for="dish in dishList" :key="dish.dishId">
                <view>
                    <view class="dish-name">{{ dish.name }}</view>
                    <view class="dish-price">￥{{ dish.price }} <text class="stock">(库存:{{ dish.currentStock }})</text></view>
                </view>
                <view class="cart-control">
                    <button class="btn-mini" @click="changeCart(dish, -1)">-</button>
                    <text class="count">{{ cart[dish.dishId] ? cart[dish.dishId].quantity : 0 }}</text>
                    <button class="btn-mini" @click="changeCart(dish, 1)">+</button>
                </view>
            </view>
        </view>

        <view class="settlement-box" v-if="totalAmount > 0">
            <view class="recommend-section" v-if="recommendations.length > 0">
                <view class="rec-title">✨ 为您页推荐 (根据喜好分析)：</view>
                <view class="rec-item" v-for="rec in recommendations" :key="rec.dishId">
                    <text>{{ rec.name }} ￥{{ rec.price }}</text>
                    <button class="btn-add" @click="changeCart(rec, 1)">加入</button>
                </view>
            </view>

            <view class="action-bar">
                <text class="total-price">合计: ￥{{ totalAmount }}</text>
                <button class="btn-submit" @click="handleCheckout">提交订单并模拟支付</button>
            </view>
        </view>
    </view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            restaurantId: '',
            dishList: [],
            cart: {},
            recommendations: []
        }
    },
    computed: {
        totalAmount() {
            return Object.values(this.cart).reduce((sum, item) => sum + item.price * item.quantity, 0);
        }
    },
    onLoad(options) {
        this.restaurantId = options.restaurantId;
        this.getDishes();
    },
    methods: {
        async getDishes() {
            try {
                const res = await request({
                    url: '/api/dishes',
                    method: 'GET',
                    data: { restaurantId: this.restaurantId }
                });
                this.dishList = res || [];
            } catch (e) {
                console.error(e);
            }
        },
        changeCart(dish, step) {
            const id = dish.dishId;
            if (!this.cart[id]) {
                if (step <= 0) return;
                this.$set(this.cart, id, { dishId: id, name: dish.name, price: dish.price, quantity: 0 });
            }
            this.cart[id].quantity += step;
            if (this.cart[id].quantity <= 0) {
                this.$delete(this.cart, id);
            }
            if (Object.keys(this.cart).length > 0 && this.recommendations.length === 0) {
                this.getRecommendations();
            }
        },
        async getRecommendations() {
            const userId = uni.getStorageSync('userId') || 'u123';
            try {
                const res = await request({
                    url: '/api/recommendations',
                    method: 'GET',
                    data: { userId }
                });
                this.recommendations = res || [];
            } catch (e) {
                console.error(e);
            }
        },
        async handleCheckout() {
            const token = uni.getStorageSync('token');
            if (!token) {
                uni.showModal({
                    title: '提示',
                    content: '请先完成快捷登录以继续订餐',
                    success: (res) => {
                        if (res.confirm) uni.navigateTo({ url: '/pages/login/login' });
                    }
                });
                return;
            }

            const items = Object.values(this.cart).map(item => ({
                dishId: item.dishId,
                quantity: item.quantity
            }));

            uni.showLoading({ title: '正在锁定库存并创单...' });

            try {
                const res = await request({
                    url: '/api/orders',
                    method: 'POST',
                    data: {
                        restaurantId: this.restaurantId,
                        items
                    }
                });

                uni.hideLoading();
                uni.showToast({ title: '模拟支付成功！', icon: 'success' });
                this.cart = {};
                setTimeout(() => {
                    uni.navigateTo({ url: `/pages/order/track?orderId=${res.orderId}` });
                }, 1200);
            } catch (e) {
                uni.hideLoading();
                uni.showToast({ title: '下单失败', icon: 'none' });
            }
        }
    }
}
</script>

<style>
.container { padding: 15px; background-color: #fff; padding-bottom: 220px; }
.section-title { font-size: 20px; font-weight: bold; margin-bottom: 15px; }
.dish-item { display: flex; justify-content: space-between; align-items: center; padding: 15px 0; border-bottom: 1px solid #eee; }
.dish-name { font-size: 16px; font-weight: bold; }
.dish-price { font-size: 14px; color: #ff5722; margin-top: 4px; }
.stock { font-size: 12px; color: #999; margin-left: 5px; }
.cart-control { display: flex; align-items: center; }
.btn-mini { width: 30px; height: 30px; line-height: 28px; text-align: center; font-size: 18px; padding: 0; background: #ff9800; color: #fff; border-radius: 50%; }
.count { margin: 0 12px; font-size: 16px; }
.settlement-box { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; box-shadow: 0 -3px 10px rgba(0,0,0,0.1); padding: 15px; border-radius: 16px 16px 0 0; }
.recommend-section { margin-bottom: 15px; background: #fff8e1; padding: 10px; border-radius: 8px; }
.rec-title { font-size: 13px; color: #ff8f00; font-weight: bold; margin-bottom: 5px; }
.rec-item { display: flex; justify-content: space-between; align-items: center; font-size: 13px; margin-top: 5px; }
.btn-add { font-size: 11px; background: #ff9800; color: #fff; padding: 2px 8px; height: 22px; line-height: 22px; border-radius: 10px; }
.action-bar { display: flex; justify-content: space-between; align-items: center; }
.total-price { font-size: 20px; font-weight: bold; color: #ff5722; }
.btn-submit { background: #4caf50; color: #fff; border-radius: 20px; font-size: 15px; padding: 0 20px; }
</style>