<template>
    <view class="container">
        <view class="content-body">
            <view class="section-title">点餐菜单</view>

			<view class="dish-list">
                <view class="dish-item" v-for="dish in dishList" :key="dish.dishId" @click="showDishDetail(dish)">
                    <view style="display:flex;">
                        <image :src="'/static/' + (dish.image || 'a1.jpg')" style="width: 50px; height: 50px; border-radius: 4px; margin-right: 10px; background:#eee;"></image>
                        <view>
                            <view class="dish-name">{{ dish.name }}</view>
                            <view style="font-size: 11px; color: #999;">销量: {{ dish.sales || 0 }}</view>
                            <view class="dish-price">￥{{ dish.price }}</view>
                        </view>
                    </view>
                    <view class="cart-control" @click.stop>
                        <button class="btn-mini" v-if="getDishCount(dish.dishId) > 0" @click.stop="changeCart(dish, -1)">-</button>
                        <text class="count" v-if="getDishCount(dish.dishId) > 0">{{ getDishCount(dish.dishId) }}</text>
                        <button class="btn-mini" @click.stop="changeCart(dish, 1)">+</button>
                    </view>
                </view>
            </view>

            <view class="reviews-section">
                <view class="section-title-review">💬 顾客真实评价</view>
                <view class="review-card-item" v-for="rev in reviewList" :key="rev.reviewId">
                    <view class="rev-header">
                        <text class="rev-user">👤 {{ rev.username }}</text>
                        <text class="rev-stars">{{ '★'.repeat(rev.rating) }}{{ '☆'.repeat(5 - rev.rating) }}</text>
                    </view>
                    <view class="rev-comment">{{ rev.comment }}</view>
                </view>
            </view>
        </view>

        <view class="settlement-box" v-if="totalAmount > 0">
            <view class="action-bar">
                <text class="total-price">合计: ￥{{ totalAmount.toFixed(2) }}</text>
                <button class="btn-submit" @click="goToSettle">去结算</button>
            </view>
        </view>
		
		<view class="modal-mask" v-if="activeDish" @click="closeDishDetail">
			<view class="dish-detail-card" @click.stop>
				<image class="detail-img" :src="'/static/' + (activeDish.image || 'a1.jpg')" mode="aspectFill"></image>
				<view class="detail-content">
					<view class="detail-name">{{ activeDish.name }}</view>
					<view class="detail-sales">月销量：{{ activeDish.sales || 0 }}份</view>
					<view class="detail-desc">{{ activeDish.description || '这道菜很美味，快来尝尝吧！' }}</view>
					<view class="detail-bottom">
						<text class="detail-price">￥{{ activeDish.price }}</text>
						<button class="btn-add-big" @click="changeCart(activeDish, 1); closeDishDetail()">加入购物车</button>
					</view>
				</view>
				<view class="close-btn" @click="closeDishDetail">✕</view>
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
            reviewList: [],
            cart: {},
			activeDish: null // 记录当前弹窗显示的菜品
        }
    },
    computed: {
        totalAmount() {
            let total = 0;
            Object.values(this.cart).forEach(i => total += i.price * i.quantity);
            return total;
        }
    },
    onLoad(options) {
        this.restaurantId = options.restaurantId;
        this.getDishes();
        this.getRestaurantReviews();
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
                uni.showToast({ title: '加载菜品失败', icon: 'none' });
            }
        },
        async getRestaurantReviews() {
            try {
                const res = await request({
                    url: '/api/reviews/restaurant',
                    method: 'GET',
                    data: { restaurantId: this.restaurantId }
                });
                this.reviewList = res || [];
            } catch (e) {
                console.error(e);
            }
        },
        getDishCount(dishId) {
            return this.cart[dishId] ? this.cart[dishId].quantity : 0;
        },
        changeCart(dish, num) {
            let c = Object.assign({}, this.cart);
            const id = dish.dishId;
            if (num > 0) {
                if (c[id]) c[id].quantity++;
                else c[id] = { dishId: id, name: dish.name, price: dish.price, quantity: 1 };
            } else {
                if (c[id]) {
                    c[id].quantity--;
                    if (c[id].quantity <= 0) delete c[id];
                }
            }
            this.cart = c;
            uni.setStorageSync('tempCart', this.cart);
            uni.setStorageSync('tempRestaurantId', this.restaurantId);
        },
        goToSettle() {
            uni.navigateTo({ url: '/pages/order/settle' });
        },
		showDishDetail(dish) {
			this.activeDish = dish;
		},
		closeDishDetail() {
			this.activeDish = null;
		}
    }
}
</script>

<style>
.container { background: #fff; min-height: 100vh; padding-bottom: 80px; }
.content-body { padding: 15px; }
.section-title { font-size: 20px; font-weight: bold; margin-bottom: 15px; }
.dish-item { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px solid #f5f5f5; }
.dish-name { font-size: 16px; font-weight: bold; }
.dish-price { font-size: 14px; color: #ff5722; font-weight: bold; }
.cart-control { display: flex; align-items: center; }
.btn-mini { width: 26px; height: 26px; display: flex; justify-content: center; align-items: center; font-size: 16px; background: #ff9800; color: #fff; border-radius: 50%; padding: 0; line-height: 1; border: none; }
.count { margin: 0 10px; font-size: 15px; }
.settlement-box{position:fixed;bottom:0;left:0;right:0;width:100%;box-sizing:border-box;background:#fff;padding:15px;box-shadow:0 -2px 10px rgba(0,0,0,0.05);}
.action-bar{display:flex;align-items:center;width:100%;}.total-price{margin-right:auto;}
.total-price { font-size: 18px; color: #ff5722; font-weight: bold; }
.btn-submit { background: #007aff; color: #fff; border-radius: 20px; padding: 0 25px; font-size: 15px; height: 36px; line-height: 36px; }
.reviews-section { margin-top: 30px; border-top: 4px solid #f5f5f5; padding-top: 15px; }
.section-title-review { font-size: 16px; font-weight: bold; margin-bottom: 12px; }
.review-card-item { background: #fdfdfd; padding: 12px; border-radius: 8px; border: 1px solid #f0f0f0; margin-bottom: 10px; }
.rev-header { display: flex; justify-content: space-between; margin-bottom: 6px; }
.rev-user { font-weight: bold; font-size: 14px; }
.rev-stars { color: #ffb300; font-size: 14px; }
.rev-comment { font-size: 14px; color: #222; }
.modal-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.6); z-index: 100; display: flex; justify-content: center; align-items: center; }
.dish-detail-card { width: 80%; background: #fff; border-radius: 12px; overflow: hidden; position: relative; }
.detail-img { width: 100%; height: 200px; background: #eee; }
.detail-content { padding: 15px; }
.detail-name { font-size: 18px; font-weight: bold; color: #333; }
.detail-sales { font-size: 12px; color: #999; margin-top: 5px; }
.detail-desc { font-size: 14px; color: #666; margin: 10px 0; line-height: 1.5; }
.detail-bottom { display: flex; justify-content: space-between; align-items: center; margin-top: 15px; }
.detail-price { font-size: 20px; color: #ff5722; font-weight: bold; }
.btn-add-big { background: #ff9800; color: #fff; border-radius: 20px; font-size: 14px; padding: 0 20px; height: 32px; line-height: 32px; margin: 0; }
.close-btn { position: absolute; top: 10px; right: 10px; width: 30px; height: 30px; line-height: 30px; text-align: center; background: rgba(0,0,0,0.5); color: #fff; border-radius: 50%; font-size: 16px; }
</style>