<template>
    <view class="container">
        <!-- 菜品大图 -->
        <view class="dish-hero" v-if="dish.image">
            <image class="hero-img" :src="'/static/' + dish.image" mode="aspectFill"></image>
        </view>
        <view class="dish-hero placeholder" v-else>
            <text class="hero-placeholder">暂无图片</text>
        </view>

        <!-- 菜品信息卡 -->
        <view class="info-card">
            <view class="name-row">
                <text class="dish-name">{{ dish.name }}</text>
                <text class="dish-price">￥{{ dish.price }}</text>
            </view>
            <view class="meta-row">
                <text class="meta-item">分类：{{ dish.category || '未分类' }}</text>
                <text class="meta-item">销量：{{ dish.sales || 0 }} 份</text>
                <text class="meta-item" :class="{ 'stock-low': dish.currentStock !== null && dish.currentStock < (dish.safetyStock || 10) }">
                    库存：{{ dish.currentStock }}
                </text>
            </view>
            <view class="desc-block">
                <text class="desc-title">菜品介绍</text>
                <text class="desc-text">{{ dish.description || '这道菜很美味，快来尝尝吧！' }}</text>
            </view>
        </view>

        <!-- 该菜品的评价 -->
        <view class="reviews-card">
            <view class="reviews-title">顾客评价（{{ reviewList.length }}）</view>
            <view class="no-review" v-if="reviewList.length === 0">
                暂无评价，快来抢首评～
            </view>
            <view class="review-item" v-for="rev in reviewList" :key="rev.reviewId">
                <view class="rev-header">
                    <text class="rev-user">👤 {{ rev.username || '匿名用户' }}</text>
                    <text class="rev-stars">{{ '★'.repeat(rev.rating) }}{{ '☆'.repeat(5 - rev.rating) }}</text>
                </view>
                <view class="rev-comment">{{ rev.comment }}</view>
            </view>
        </view>

        <!-- 底部操作栏 -->
        <view class="action-bar">
            <view class="cart-control">
                <button class="btn-mini" :disabled="quantity <= 0" @click="quantity > 0 && quantity--">-</button>
                <text class="count">{{ quantity }}</text>
                <button class="btn-mini" @click="quantity++">+</button>
            </view>
            <button class="btn-add-cart" @click="addToCart">加入购物车</button>
        </view>
    </view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            dishId: '',
            restaurantId: '',
            dish: {},
            reviewList: [],
            quantity: 1
        }
    },
    onLoad(options) {
        this.dishId = options.dishId;
        this.restaurantId = options.restaurantId;
        if (options.dish) {
            // 从菜单页透传菜品基本信息，避免首屏空白
            try {
                this.dish = JSON.parse(decodeURIComponent(options.dish));
            } catch (e) {
                this.dish = {};
            }
        }
        this.fetchDishReviews();
    },
    methods: {
        async fetchDishReviews() {
            try {
                const res = await request({
                    url: '/api/reviews/dish',
                    method: 'GET',
                    data: { dishId: this.dishId }
                });
                this.reviewList = res || [];
            } catch (e) {
                console.error('拉取菜品评价失败', e);
            }
        },
        addToCart() {
            if (this.quantity <= 0) {
                uni.showToast({ title: '请选择数量', icon: 'none' });
                return;
            }

            // 复用 menu.vue 的购物车存储结构（tempCart + tempRestaurantId）
            const savedCart = uni.getStorageSync('tempCart') || {};
            const id = this.dishId;
            if (savedCart[id]) {
                savedCart[id].quantity += this.quantity;
            } else {
                savedCart[id] = {
                    dishId: id,
                    name: this.dish.name,
                    price: this.dish.price,
                    quantity: this.quantity
                };
            }
            uni.setStorageSync('tempCart', savedCart);
            uni.setStorageSync('tempRestaurantId', this.restaurantId);

            uni.showToast({ title: '已加入购物车', icon: 'success' });
            setTimeout(() => uni.navigateBack(), 600);
        }
    }
}
</script>

<style>
.container { background: #f7f7f7; min-height: 100vh; padding-bottom: 80px; }
.dish-hero { width: 100%; height: 220px; background: #eee; }
.hero-img { width: 100%; height: 100%; }
.placeholder { display: flex; align-items: center; justify-content: center; }
.hero-placeholder { color: #bbb; font-size: 14px; }

.info-card { background: #fff; margin: 12px; padding: 16px; border-radius: 12px; }
.name-row { display: flex; justify-content: space-between; align-items: center; }
.dish-name { font-size: 20px; font-weight: bold; color: #222; flex: 1; }
.dish-price { font-size: 20px; font-weight: bold; color: #ff5722; }
.meta-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.meta-item { font-size: 12px; color: #888; background: #f5f5f5; padding: 4px 10px; border-radius: 12px; }
.stock-low { color: #ff5722; background: #fff0e0; }
.desc-block { margin-top: 14px; border-top: 1px dashed #eee; padding-top: 12px; }
.desc-title { font-size: 14px; font-weight: bold; color: #333; display: block; margin-bottom: 6px; }
.desc-text { font-size: 14px; color: #666; line-height: 1.6; }

.reviews-card { background: #fff; margin: 12px; padding: 16px; border-radius: 12px; }
.reviews-title { font-size: 15px; font-weight: bold; color: #333; margin-bottom: 12px; }
.no-review { text-align: center; color: #bbb; font-size: 13px; padding: 20px 0; }
.review-item { padding: 12px 0; border-bottom: 1px solid #f5f5f5; }
.review-item:last-child { border-bottom: none; }
.rev-header { display: flex; justify-content: space-between; margin-bottom: 6px; }
.rev-user { font-size: 13px; font-weight: bold; color: #333; }
.rev-stars { color: #ffb300; font-size: 13px; }
.rev-comment { font-size: 13px; color: #555; line-height: 1.5; }

.action-bar { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 -2px 10px rgba(0,0,0,0.05); }
.cart-control { display: flex; align-items: center; }
.btn-mini { width: 32px; height: 32px; line-height: 30px; text-align: center; font-size: 18px; padding: 0; background: #ff9800; color: #fff; border-radius: 50%; border: none; }
.btn-mini[disabled] { background: #ddd; }
.count { margin: 0 14px; font-size: 16px; min-width: 20px; text-align: center; }
.btn-add-cart { background: #007aff; color: #fff; border-radius: 22px; font-size: 15px; height: 40px; line-height: 40px; padding: 0 30px; border: none; }
</style>
