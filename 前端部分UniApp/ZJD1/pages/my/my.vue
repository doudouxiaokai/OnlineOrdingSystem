<template>
    <view class="container">
        <view class="user-card">
            <view class="avatar">👤</view>
            <view class="user-info">
                <view class="username">{{ username || '游客用户' }}</view>
                <view class="phone">{{ phone ? maskPhone(phone) : '点击登录解锁更多尊享权益' }}</view>
            </view>
            <button class="btn-login-status" v-if="!isLogin" @click="toLogin">去登录</button>
        </view>

        <view class="address-section" v-if="isLogin">
            <view class="section-title">📍 管理我的收货地址</view>
            <view class="address-list">
                <view class="address-item-mini" v-for="addr in addressList" :key="addr.addressId" @click="editExistAddress(addr)">
                    <text class="addr-text">{{ addr.receiverName }} ({{ addr.receiverPhone }}) - {{ addr.detailAddress }}</text>
                    <text class="edit-icon">✏️</text>
                </view>
            </view>

            <view class="address-form">
                <input class="input-field" placeholder="收货人姓名" v-model="form.receiverName" />
                <input class="input-field" placeholder="手机号" v-model="form.receiverPhone" />
                <input class="input-field" placeholder="详细地址" v-model="form.detailAddress" />
                <button class="btn-save-addr" @click="saveAddress">保存当前收货地址</button>
            </view>
        </view>

		<view class="menu-list-box" style="margin-top: 20px;">
            <view class="menu-item" @click="toAdmin" v-if="isLogin" style="border-bottom: 1px solid #f5f5f5;">
                <text class="icon">⚙️</text>
                <text class="title">进入管理模式</text>
                <text class="arrow">></text>
            </view>
            
            <view class="menu-item" @click="logout" v-if="isLogin">
                <text class="icon">🚪</text>
                <text class="title text-red">退出当前账户</text>
                <text class="arrow">></text>
            </view>
        </view>
    </view>
	
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            isLogin: false,
            username: '',
            phone: '',
            addressList: [],
            form: { addressId: '', userId: '', receiverName: '', receiverPhone: '', detailAddress: '' }
        }
    },
    onShow() {
        this.userId = uni.getStorageSync('userId');
        this.username = uni.getStorageSync('username');
        this.phone = uni.getStorageSync('phone') || '';
        if (this.userId) {
            this.isLogin = true;
            this.loadAddresses();
        }
    },
    methods: {
		toAdmin() {
            uni.navigateTo({ url: '/pages/admin/admin' });
        },
        async loadAddresses() {
            try {
                const res = await request({
                    url: '/api/address',
                    method: 'GET',
                    data: { userId: this.userId }
                });
                this.addressList = res || [];
            } catch (e) {
                console.error(e);
            }
        },
        editExistAddress(addr) {
            this.form = { ...addr };
        },
        async saveAddress() {
            if (!this.form.receiverName || !this.form.receiverPhone || !this.form.detailAddress) {
                uni.showToast({ title: '请填写完整地址信息', icon: 'none' });
                return;
            }
            this.form.userId = this.userId;
            try {
                await request({
                    url: '/api/address/save',
                    method: 'POST',
                    data: this.form
                });
                uni.showToast({ title: '地址保存成功' });
                this.form = { addressId: '', userId: '', receiverName: '', receiverPhone: '', detailAddress: '' };
                this.loadAddresses();
            } catch (e) {
                console.error(e);
            }
        },
        toLogin() {
            uni.navigateTo({ url: '/pages/login/login' });
        },
        logout() {
            uni.clearStorageSync();
            this.isLogin = false;
            uni.showToast({ title: '已退出登录' });
        },
        maskPhone(val) {
            return val.length > 7 ? val.substring(0, 3) + '****' + val.substring(7) : val;
        }
    }
}
</script>

<style>
.container { padding: 15px; background: #f7f7f7; min-height: 100vh; }
.user-card { background: #007aff; color: #fff; padding: 25px 20px; border-radius: 12px; display: flex; align-items: center; position: relative; }
.avatar { font-size: 35px; margin-right: 15px; }
.address-section { background: #fff; padding: 15px; border-radius: 12px; margin-top: 15px; }
.section-title { font-size: 16px; font-weight: bold; margin-bottom: 10px; }
.address-item-mini { padding: 12px; border: 1px solid #e5e5e5; border-radius: 8px; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; font-size: 13px; background: #fff;}
.input-field { border: 1px solid #ddd; padding: 8px; border-radius: 6px; margin-top: 8px; font-size: 14px; }
.btn-save-addr { background: #4caf50; color: #fff; font-size: 14px; margin-top: 10px; border-radius: 6px; }
.menu-item { display: flex; align-items: center; background: #fff; padding: 15px; border-radius: 8px; }
.text-red { color: red; }
</style>