<template>
  <view class="login-container">
    <view class="title">手机号快捷登录</view>

    <input
      class="input"
      type="number"
      v-model="phone"
      placeholder="请输入手机号"
      maxlength="11"
    />

    <button class="btn-login" @click="handleLogin">
      登录 / 注册
    </button>
  </view>
</template>

<script>
// 🌟 统一引入封装好的网络请求工具
import { request } from '@/utils/request.js';

export default {
  data() {
    return {
      phone: ''
    }
  },
  methods: {
    async handleLogin() {
      if (!/^1[3-9]\d{9}$/.test(this.phone)) {
        uni.showToast({ title: '请输入正确的手机号', icon: 'none' });
        return;
      }

      try {
        // 🌟 改为走统一的 request 方法
        const res = await request({
          url: '/api/auth/login',
          method: 'POST',
          data: {
            phone: this.phone
          }
        });

        console.log('登录成功，返回数据：', res);

        // 存储用户信息
        if (res && res.token) {
          uni.setStorageSync('token', res.token);
          uni.setStorageSync('userId', res.userId);
          uni.setStorageSync('username', res.username);
          uni.setStorageSync('phone', res.phone); // 存入手机号方便"我的"页面同步展示

          uni.showToast({ title: '登录成功', icon: 'success' });

          setTimeout(() => {
            uni.navigateBack();
          }, 1000);
        } else {
          uni.showToast({ title: '登录失败，无有效数据', icon: 'none' });
        }

      } catch (err) {
        console.error('登录请求抛出异常：', err);
        // 如果后端报错，底层 request.js 已经内置了 uni.showToast 容错提示
      }
    }
  }
}
</script>

<style>
.login-container { padding: 40px 20px; background-color: #fff; min-height: 100vh; display: flex; flex-direction: column; align-items: center; }
.title { font-size: 22px; font-weight: bold; margin-bottom: 40px; color: #333; width: 100%; text-align: left; }
.input { width: 100%; height: 45px; border-bottom: 1px solid #ddd; font-size: 16px; margin-bottom: 30px; padding: 0 5px; box-sizing: border-box; }
.btn-login { width: 100%; height: 45px; line-height: 45px; background-color: #007aff; color: #fff; border-radius: 25px; font-size: 16px; font-weight: bold; margin-top: 10px; }
.btn-login::after { border: none; }
</style>