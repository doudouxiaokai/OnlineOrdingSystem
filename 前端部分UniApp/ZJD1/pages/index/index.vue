<template>
    <view class="container">
        <view class="header-title">附近餐厅</view>
        <view class="section-title">推荐好店</view>

        <view class="restaurant-card" v-for="item in restaurantList" :key="item.restaurantId" @click="goToDetail(item.restaurantId)">
            <view class="res-name">{{ item.name }}</view>
            <view class="res-info">★ {{ item.rating }} | {{ item.address }}</view>
        </view>
    </view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            restaurantList: []
        }
    },
    onShow() {
        this.getRestaurants();
    },
    methods: {
        async getRestaurants() {
            try {
                const res = await request({
                    url: '/api/restaurants',
                    method: 'GET'
                });
                this.restaurantList = res || [];
            } catch (e) {
                uni.showToast({ title: '网络连接失败', icon: 'none' });
            }
        },
        goToDetail(restaurantId) {
            uni.navigateTo({
                url: `/pages/menu/menu?restaurantId=${restaurantId}`
            });
        }
    }
}
</script>

<style>
.container { padding: 20px; background-color: #f9f9f9; min-height: 100vh; }
.header-title { font-size: 24px; font-weight: bold; text-align: center; margin-bottom: 20px; }
.section-title { font-size: 18px; font-weight: bold; margin-bottom: 15px; color: #333; }
.restaurant-card { background: #fff; padding: 15px; border-radius: 8px; margin-bottom: 12px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }
.res-name { font-size: 18px; font-weight: bold; color: #111; }
.res-info { font-size: 14px; color: #666; margin-top: 5px; }
</style>