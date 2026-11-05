<template>
    <view class="container">
        <view class="card-box address-card" @click="openAddressModal">
            <view class="address-header">
                <text class="address-icon">📍</text>
                <view class="address-info-block">
                    <view class="address-title">
                        {{ selectedAddress.detailAddress || '请选择或输入收货地址' }}
                    </view>
                    <view class="address-user" v-if="selectedAddress.receiverName">
                        {{ selectedAddress.receiverName }} （{{ selectedAddress.receiverPhone }}）
                    </view>
                </view>
                <text class="arrow-right">></text>
            </view>
        </view>

        <view class="card-box">
            <view class="section-title">订单明细</view>
            <view class="order-item" v-for="item in cartItems" :key="item.dishId">
                <text class="dish-name">{{ item.name }}</text>
                <text class="dish-qty">x{{ item.quantity }}</text>
                <text class="dish-price">￥{{ (item.price * item.quantity).toFixed(2) }}</text>
            </view>
            <view class="total-bar" v-if="cartItems.length > 0">
                <text>小计：</text>
                <text class="price-tag">￥{{ totalAmount.toFixed(2) }}</text>
            </view>
        </view>

        <view class="card-box recommend-box" v-if="recommendations.length > 0">
            <view class="section-title text-orange">✨ 为您精准推荐（基于历史常点口味）</view>
            <view class="rec-item" v-for="rec in recommendations" :key="rec.dishId">
                <view class="rec-info">
                    <text class="rec-name">{{ rec.name }}</text>
                    <text class="rec-price">￥{{ rec.price }}</text>
                </view>
                <button class="btn-add-mini" @click="addRecommendToOrder(rec)">加一份</button>
            </view>
        </view>

        <view class="action-fixed">
            <button class="btn-submit-pay" @click="handleCreateOrder">确认下单</button>
        </view>

        <view class="modal-mask" v-if="showAddressModal" @click="closeAddressModal">
            <view class="modal-content" @click.stop>
                <view class="modal-header">
                    <text class="modal-title">选择收货地址</text>
                    <text class="modal-close" @click="closeAddressModal">✕</text>
                </view>
                
                <scroll-view scroll-y class="address-list-scroll">
                    <view 
                        class="address-list-item" 
                        :class="{ 'address-active': item.addressId === selectedAddress.addressId }"
                        v-for="item in addressList" 
                        :key="item.addressId"
                        @click="selectThisAddress(item)"
                    >
                        <view class="addr-row1">
                            <text class="addr-tag" v-if="item.tag">{{ item.tag }}</text>
                            <text class="addr-detail">{{ item.detailAddress }}</text>
                        </view>
                        <view class="addr-row2">{{ item.receiverName }} - {{ item.receiverPhone }}</view>
                    </view>
                    <view class="no-data-text" v-if="addressList.length === 0" style="text-align: center; color: #999; padding: 20px 0; font-size: 13px;">
                        暂无收货地址，请在下方手动添加
                    </view>
                </scroll-view>

                <view class="add-address-form">
                    <view class="form-title">➕ 手动添加新收货地址</view>
                    <input class="form-input" type="text" placeholder="收货人姓名" v-model="newAddr.receiverName" />
                    <input class="form-input" type="number" placeholder="手机号码" v-model="newAddr.receiverPhone" maxlength="11" />
                    <input class="form-input" type="text" placeholder="详细收货地址 (如：xx街道xx号楼)" v-model="newAddr.detailAddress" />
                    <button class="btn-save-addr" @click="saveNewAddress">保存并使用该新地址</button>
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
            restaurantId: '',
            cartItems: [],
            recommendations: [],
            showAddressModal: false,
            selectedAddress: {},
            addressList: [],
            newAddr: {
                receiverName: '',
                receiverPhone: '',
                detailAddress: '',
                tag: '自定义'
            }
        }
    },
    computed: {
        totalAmount() {
            return this.cartItems.reduce((sum, item) => sum + item.price * item.quantity, 0);
        }
    },
    onShow() {
        const savedResId = uni.getStorageSync('tempRestaurantId');
        const savedCart = uni.getStorageSync('tempCart') || {};
        
        this.restaurantId = savedResId || '';
        this.cartItems = Object.keys(savedCart).map(id => savedCart[id]) || [];

        this.getRecommendations();
        this.fetchUserAddresses();
    },
    methods: {
        async fetchUserAddresses() {
            const userId = uni.getStorageSync('userId');
            if (!userId) return;
            
            try {
                const res = await request({
                    url: `/api/address?userId=${userId}`,
                    method: 'GET'
                });
                this.addressList = res || [];
                if (this.addressList.length > 0 && !this.selectedAddress.addressId) {
                    this.selectedAddress = this.addressList[0];
                }
            } catch (err) {
                console.error('拉取地址失败:', err);
            }
        },
        async getRecommendations() {
            const userId = uni.getStorageSync('userId') || 'u1';
			const restaurantId = uni.getStorageSync('tempRestaurantId')
            try {
                const res = await request({
                    url: `/api/recommendations?userId=${userId}&restaurantId=${restaurantId}`,
                    method: 'GET'
                });
                this.recommendations = res || [];
            } catch (err) {
                console.error('获取推荐失败:', err);
            }
        },
        addRecommendToOrder(dish) {
            const exist = this.cartItems.find(item => item.dishId === dish.dishId);
            if (exist) {
                exist.quantity += 1;
            } else {
                this.cartItems.push({
                    dishId: dish.dishId,
                    name: dish.name,
                    price: dish.price,
                    quantity: 1
                });
            }
            const savedCart = uni.getStorageSync('tempCart') || {};
            if (savedCart[dish.dishId]) {
                savedCart[dish.dishId].quantity += 1;
            } else {
                savedCart[dish.dishId] = {
                    dishId: dish.dishId,
                    name: dish.name,
                    price: dish.price,
                    quantity: 1
                };
            }
            uni.setStorageSync('tempCart', savedCart);
        },
        
        openAddressModal() {
            this.showAddressModal = true;
        },
        closeAddressModal() {
            this.showAddressModal = false;
        },
        selectThisAddress(item) {
            this.selectedAddress = item;
            this.showAddressModal = false;
            uni.showToast({ title: '已切换收货地址', icon: 'none' });
        },
        async saveNewAddress() {
            const userId = uni.getStorageSync('userId');
            if (!userId) {
                uni.showToast({ title: '请先登录账户', icon: 'none' });
                return;
            }
            if (!this.newAddr.receiverName || !this.newAddr.receiverPhone || !this.newAddr.detailAddress) {
                uni.showToast({ title: '请完整输入姓名、电话和地址', icon: 'none' });
                return;
            }
            if (!(/^1[3-9]\d{9}$/.test(this.newAddr.receiverPhone))) {
                uni.showToast({ title: '手机号格式不正确', icon: 'none' });
                return;
            }

            uni.showLoading({ title: '正在保存地址...', mask: true });

            try {
                await request({
                    url: '/api/address/save',
                    method: 'POST',
                    data: {
                        userId: userId,
                        receiverName: this.newAddr.receiverName,
                        receiverPhone: this.newAddr.receiverPhone,
                        detailAddress: this.newAddr.detailAddress
                    }
                });
                
                uni.hideLoading();
                uni.showToast({ title: '地址添加成功', icon: 'success' });
                
                this.newAddr = { receiverName: '', receiverPhone: '', detailAddress: '', tag: '自定义' };
                this.showAddressModal = false; 
                
                await this.fetchUserAddresses();
                if (this.addressList.length > 0) {
                    this.selectedAddress = this.addressList[this.addressList.length - 1]; 
                    await this.fetchUserAddresses();
                }
            } catch (err) {
                uni.hideLoading();
                console.error('保存地址失败:', err);
                uni.showToast({ title: '保存地址失败，请重试', icon: 'none' });
            }
        },

        async handleCreateOrder() {
			const userId = uni.getStorageSync('userId');
			if (!userId) {
				uni.showToast({ title: '请先登录账户', icon: 'none' });
				setTimeout(() => {
					uni.navigateTo({ url: '/pages/login/login' });
				}, 1000);
				return;
			}

			if (!this.selectedAddress.detailAddress) {
				uni.showToast({ title: '请选择或输入收货地址', icon: 'none' });
				return;
			}

			if (this.cartItems.length === 0) {
				uni.showToast({ title: '购物车是空的', icon: 'none' });
				return;
			}

			const orderItems = this.cartItems.map(item => ({
				dishId: item.dishId,
				quantity: item.quantity
			}));

			uni.showLoading({ title: '正在下单中...', mask: true });

			try {
                const res = await request({
					
                    url: '/api/orders',
                    method: 'POST',
                    data: {
                        userId: userId,
                        restaurantId: this.restaurantId,
                        items: orderItems,
                        address: `${this.selectedAddress.receiverName} (${this.selectedAddress.receiverPhone}) ${this.selectedAddress.detailAddress}`,
                        needPay: true
                    }
                });

				uni.hideLoading();
				if (res && res.orderId) {
					// 【修改点】清空购物车，然后跳转到支付页面
					uni.removeStorageSync('tempCart'); 
					
					uni.showToast({ title: '订单创建成功！', icon: 'success' });
					setTimeout(() => {
						// 跳转到新创建的支付页面
						uni.navigateTo({
							url: `/pages/order/pay?orderId=${res.orderId}&totalAmount=${res.totalAmount}&restaurantId=${this.restaurantId}`
						});
					}, 800);
				} else {
					uni.showToast({ title: '创建订单失败', icon: 'none' });
				}
			} catch (err) {
				uni.hideLoading();
				console.error(err);
				uni.showToast({ title: '创建订单失败', icon: 'none' });
			}
		}
    }
}
</script>

<style>
.container { padding: 15px; background: #f7f7f7; min-height: 100vh; padding-bottom: 80px; box-sizing: border-box; }
.card-box { background: #fff; padding: 15px; border-radius: 12px; margin-bottom: 12px; box-shadow: 0 2px 6px rgba(0,0,0,0.02); }
.address-card { border-left: 5px solid #007aff; }
.address-header { display: flex; align-items: center; justify-content: space-between; }
.address-icon { font-size: 20px; margin-right: 10px; }
.address-info-block { flex: 1; }
.address-title { font-size: 15px; font-weight: bold; color: #333; word-break: break-all; }
.address-user { font-size: 13px; color: #666; margin-top: 4px; }
.arrow-right { color: #ccc; font-size: 16px; margin-left: 10px; }

.section-title { font-size: 15px; font-weight: bold; margin-bottom: 12px; color: #333; border-left: 4px solid #007aff; padding-left: 8px; }
.text-orange { color: #ff9800; border-left-color: #ff9800; }
.order-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px dashed #f0f0f0; font-size: 14px; }
.dish-name { flex: 2; color: #222; }
.dish-qty { flex: 1; text-align: center; color: #888; }
.dish-price { flex: 1; text-align: right; font-weight: bold; color: #333; }
.total-bar { display: flex; justify-content: flex-end; align-items: center; margin-top: 15px; font-size: 14px; font-weight: bold; }
.price-tag { color: #ff5722; font-size: 18px; margin-left: 5px; }

.recommend-box { background: #fff8e1; border: 1px solid #ffe0b2; }
.rec-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px dashed #fff0db; }
.rec-info { display: flex; flex-direction: column; }
.rec-name { font-size: 14px; font-weight: bold; color: #5d4037; }
.rec-price { font-size: 13px; color: #ff5722; font-weight: bold; margin-top: 2px; }
.btn-add-mini { background: #ff9800; color: #fff; font-size: 12px; padding: 0 12px; height: 26px; line-height: 26px; margin: 0; border-radius: 12px; border: none; }

.action-fixed { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 12px 20px; box-shadow: 0 -2px 10px rgba(0,0,0,0.05); display: flex; justify-content: center; z-index: 90; }
.btn-submit-pay { background: #007aff; color: #fff; width: 100%; border-radius: 22px; font-size: 16px; font-weight: bold; height: 44px; line-height: 44px; }

.modal-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 999; display: flex; align-items: flex-end; }
.modal-content { background: #fff; width: 100%; max-height: 75vh; border-radius: 16px 16px 0 0; padding: 20px; box-sizing: border-box; display: flex; flex-direction: column; }
.modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; }
.modal-title { font-size: 16px; font-weight: bold; color: #333; }
.modal-close { font-size: 18px; color: #999; padding: 5px; }

.address-list-scroll { max-height: 30vh; margin-bottom: 15px; border-bottom: 1px solid #f5f5f5; }
.address-list-item { padding: 12px; border: 1px solid #eee; border-radius: 8px; margin-bottom: 10px; background: #fafafa; transition: all 0.2s; }
.address-active { border-color: #007aff; background: #eef6ff; }
.addr-row1 { display: flex; align-items: center; margin-bottom: 5px; }
.addr-tag { background: #ff9800; color: #fff; font-size: 11px; padding: 1px 5px; border-radius: 3px; margin-right: 6px; }
.addr-detail { font-size: 14px; font-weight: bold; color: #333; }
.addr-row2 { font-size: 12px; color: #666; }

.add-address-form { background: #f9f9f9; padding: 12px; border-radius: 8px; border: 1px dashed #ddd; }
.form-title { font-size: 13px; font-weight: bold; color: #666; margin-bottom: 10px; }
.form-input { background: #fff; height: 36px; border: 1px solid #ddd; border-radius: 4px; padding: 0 10px; margin-bottom: 8px; font-size: 13px; box-sizing: border-box; }
.btn-save-addr { background: #4cd964; color: #fff; font-size: 14px; font-weight: bold; height: 36px; line-height: 36px; border-radius: 4px; border: none; }
</style>