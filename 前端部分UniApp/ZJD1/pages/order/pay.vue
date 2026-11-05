<template>
	<view class="container">
		<!-- 订单金额卡片 -->
		<view class="amount-card">
			<text class="amount">￥{{ totalAmount }}</text>
			<text class="order-no">订单号：{{ orderNo }}</text>
			<view class="countdown">
				请在
				<text class="time">{{ formatCountdown(countdown) }}</text>
				内完成支付
			</view>
		</view>

		<!-- 支付方式 -->
		<view class="pay-method-card">
			<view class="section-title">选择支付方式</view>

			<view class="pay-item" :class="{ active: payMethod === 'WECHAT' }" @click="payMethod = 'WECHAT'">
				<image src="/static/wechat.png" class="icon"></image>
				<text class="name">微信支付</text>
				<view class="check" v-if="payMethod === 'WECHAT'">✓</view>
			</view>

			<view class="pay-item" :class="{ active: payMethod === 'ALIPAY' }" @click="payMethod = 'ALIPAY'">
				<image src="/static/alipay.png" class="icon"></image>
				<text class="name">支付宝</text>
				<view class="check" v-if="payMethod === 'ALIPAY'">✓</view>
			</view>

			<view class="pay-item" :class="{ active: payMethod === 'BANK_CARD' }" @click="payMethod = 'BANK_CARD'">
				<image src="/static/bank.png" class="icon"></image>
				<text class="name">银行卡支付</text>
				<view class="check" v-if="payMethod === 'BANK_CARD'">✓</view>
			</view>
		</view>

		<!-- 底部操作栏 -->
		<view class="footer">
			<button class="btn-pay" :disabled="!payMethod || paying" @click="handlePay">
				{{ paying ? '支付中...' : `确认支付 ￥${totalAmount}` }}
			</button>
			<!-- ✅ 新增按钮容器：取消订单 + 返回店铺并排 -->
			<view class="footer-btns">
				<button class="btn-cancel" @click="handleCancel">取消订单</button>
				<button class="btn-back-shop" @click="handleBackToShop">返回店铺</button>
			</view>
		</view>

		<!-- 模拟支付弹窗 -->
		<view class="modal-mask" v-if="showModal" @click="showModal = false">
			<view class="modal-content" @click.stop>
				<view class="modal-header">模拟支付</view>
				<view class="modal-body">
					<text>支付方式：{{ payMethodText }}</text>
					<text>支付金额：￥{{ totalAmount }}</text>
					<text class="tip">这是模拟环境，点击确认即可完成支付</text>
				</view>
				<view class="modal-footer">
					<button class="btn-confirm" @click="confirmPay">确认支付</button>
					<button class="btn-close" @click="showModal = false">再想想</button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
	data() {
		return {
			orderId: '',
			orderNo: '',
			totalAmount: '0.00',
			restaurantId: '', // ✅ 新增：存储订单对应的店铺ID
			countdown: 600,
			payMethod: 'WECHAT',
			paying: false,
			showModal: false,
			timer: null
		};
	},
	computed: {
		payMethodText() {
			const map = { WECHAT: '微信支付', ALIPAY: '支付宝', BANK_CARD: '银行卡支付' };
			return map[this.payMethod] || '';
		}
	},
	onLoad(options) {
		this.orderId = options.orderId;
		this.totalAmount = options.totalAmount || '0.00';
		this.orderId = options.orderId;
		this.fetchOrderDetail();
	},
	onUnload() {
		if (this.timer) clearInterval(this.timer);
	},
	methods: {
		async fetchOrderDetail() {
			try {
				const res = await request({
					url: `/api/orders/unpaid/${this.orderId}`,
					method: 'GET'
				});
				
				this.orderNo = res.orderNo;
				this.restaurantId = res.restaurantId; // ✅ 保存店铺ID
				
				
				if (res.remainSeconds > 0) {
					this.countdown = res.remainSeconds;
					this.startTimer();
				} else {
					this.handleTimeout();
				}
			} catch (e) {
				uni.showToast({ title: '订单已失效', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 1500);
			}
		},
		startTimer() {
			this.timer = setInterval(() => {
				if (this.countdown <= 0) {
					this.handleTimeout();
					return;
				}
				this.countdown--;
			}, 1000);
		},
		handleTimeout() {
			clearInterval(this.timer);
			uni.showModal({
				title: '支付超时',
				content: '订单已自动关闭，请重新下单',
				showCancel: false,
				success: () => uni.navigateBack()
			});
		},
		handlePay() {
			if (!this.payMethod) {
				uni.showToast({ title: '请选择支付方式', icon: 'none' });
				return;
			}
			this.showModal = true;
		},
		async confirmPay() {
			this.paying = true;
			this.showModal = false;
			try {
				await request({
					url: '/api/orders/pay',
					method: 'POST',
					data: { orderId: this.orderId, payMethod: this.payMethod }
				});
				uni.showToast({ title: '支付成功', icon: 'success' });
				setTimeout(() => {
					uni.redirectTo({
						url: `/pages/order/track?orderId=${this.orderId}&source=pay`
					});
				}, 1200);
			} catch (e) {
				uni.showToast({ title: e.message || '支付失败', icon: 'none' });
			} finally {
				this.paying = false;
			}
		},
		async handleCancel() {
			uni.showModal({
				title: '取消订单',
				content: '确定要取消此订单吗？',
				success: async (res) => {
					if (res.confirm) {
						try {
							await request({
								url: '/api/orders/cancel',
								method: 'POST',
								data: { orderId: this.orderId, reason: '用户主动取消' }
							});
							uni.showToast({ title: '订单已取消' });
							setTimeout(() => uni.navigateBack(), 1200);
						} catch (e) {
							uni.showToast({ title: e.message || '取消失败', icon: 'none' });
						}
					}
				}
			});
		},
		// ✅ 新增：返回店铺逻辑
		handleBackToShop() {
			if (!this.restaurantId) {
				uni.showToast({ title: '店铺信息不存在', icon: 'none' });
				return;
			}
			uni.showModal({
				title: '返回店铺',
				content: '订单仍处于待支付状态，你可稍后在「我的订单」中继续支付',
				confirmText: '确认返回',
				cancelText: '再想想',
				success: (res) => {
					if (res.confirm) {
						// 跳转到对应店铺菜单页，关闭当前支付页避免返回栈堆积
						uni.redirectTo({
							url: `/pages/menu/menu?restaurantId=${this.restaurantId}`,
							fail: () => {
								uni.showToast({ title: '跳转失败', icon: 'none' });
							}
						});
					}
				}
			});
		},
		formatCountdown(s) {
			const m = Math.floor(s / 60);
			const sec = s % 60;
			return `${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
		}
	}
};
</script>

<style>
.container { padding: 20px; background: #f7f7f7; min-height: 100vh; padding-bottom: 120px; }
.amount-card { background: linear-gradient(135deg, #ff5722, #ff9800); color: #fff; border-radius: 12px; padding: 30px 20px; text-align: center; margin-bottom: 20px; }
.amount { font-size: 42px; font-weight: bold; display: block; }
.order-no { font-size: 13px; opacity: 0.9; margin: 10px 0; display: block; }
.countdown { font-size: 14px; }
.time { font-weight: bold; background: rgba(0,0,0,0.2); padding: 2px 6px; border-radius: 4px; }

.pay-method-card { background: #fff; border-radius: 12px; padding: 15px; }
.section-title { font-size: 16px; font-weight: bold; margin-bottom: 15px; }
.pay-item { display: flex; align-items: center; padding: 15px 0; border-bottom: 1px solid #f5f5f5; position: relative; }
.pay-item.active { background: #f0f9ff; }
.icon { width: 28px; height: 28px; margin-right: 12px; }
.name { flex: 1; font-size: 15px; }
.check { color: #07c160; font-weight: bold; font-size: 18px; }

.footer { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 15px 20px; box-shadow: 0 -2px 10px rgba(0,0,0,0.05); }
.btn-pay { background: #07c160; color: #fff; height: 50px; line-height: 50px; border-radius: 25px; font-size: 16px; font-weight: bold; }
.btn-pay[disabled] { background: #ccc; }

/* ✅ 新增：按钮容器样式 */
.footer-btns {
	display: flex;
	gap: 12px;
	margin-top: 10px;
}
/* ✅ 修改：取消按钮和返回店铺按钮同宽 */
.btn-cancel {
	flex: 1;
	height: 40px;
	line-height: 40px;
	border-radius: 20px;
	font-size: 14px;
	background: transparent;
	color: #666;
	border: 1px solid #eee;
}
.btn-cancel::after { border: none; }

/* ✅ 新增：返回店铺按钮样式 */
.btn-back-shop {
	flex: 1;
	height: 40px;
	line-height: 40px;
	border-radius: 20px;
	font-size: 14px;
	background: #f0f9ff;
	color: #007aff;
	border: 1px solid #cce5ff;
}
.btn-back-shop::after { border: none; }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 999; display: flex; align-items: center; justify-content: center; }
.modal-content { background: #fff; width: 85%; border-radius: 12px; overflow: hidden; }
.modal-header { padding: 15px; font-size: 16px; font-weight: bold; text-align: center; border-bottom: 1px solid #f5f5f5; }
.modal-body { padding: 20px; display: flex; flex-direction: column; gap: 10px; align-items: center; }
.tip { color: #ff5722; font-size: 13px; margin-top: 10px; }
.modal-footer { display: flex; border-top: 1px solid #f5f5f5; }
.btn-confirm, .btn-close { flex: 1; margin: 0; border-radius: 0; height: 48px; line-height: 48px; }
.btn-confirm { background: #07c160; color: #fff; }
.btn-close { background: #f5f5f5; color: #666; }
</style>