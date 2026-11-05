<template>
    <view class="container">
        <view class="title">🛠️ 后台管理模式</view>

        <view class="admin-card">
            <view class="card-title">1. 添加新店铺</view>
            <input class="input" placeholder="店铺名称" v-model="resForm.name" />
            <input class="input" placeholder="店铺地址" v-model="resForm.address" />
            <button class="btn" @click="addRestaurant">确认添加店铺</button>
        </view>

        <view class="admin-card">
            <view class="card-title">2. 为店铺添加新菜品</view>
            <!-- 改为选择器 -->
            <picker mode="selector" :range="restaurantList" range-key="name" @change="onRestaurantChange">
                <view class="input picker-input">
                    {{ dishForm.restaurantId ? selectedRestaurantName : '请选择店铺' }}
                </view>
            </picker>
            <input class="input" placeholder="菜品名称" v-model="dishForm.name" />
            <input class="input" type="digit" placeholder="价格" v-model="dishForm.price" />
            <input class="input" type="number" placeholder="初始库存" v-model="dishForm.currentStock" />
            <button class="btn" @click="addDish">确认添加菜品</button>
        </view>

        <view class="admin-card">
            <view class="card-title">3. 增加菜品库存</view>
            <!-- 改为选择器：先选店铺 -->
            <picker mode="selector" :range="restaurantList" range-key="name" @change="onStockRestaurantChange">
                <view class="input picker-input">
                    {{ stockForm.restaurantId ? stockRestaurantName : '请选择店铺' }}
                </view>
            </picker>
            
            <!-- 改为选择器：再选菜品 -->
            <picker 
                mode="selector" 
                :range="filteredDishList" 
                range-key="name" 
                @change="onDishChange"
                :disabled="!stockForm.restaurantId"
            >
                <view class="input picker-input" :class="{disabled: !stockForm.restaurantId}">
                    {{ stockForm.dishId ? selectedDishName : '请先选择店铺，再选择菜品' }}
                </view>
            </picker>
            
            <input class="input" type="number" placeholder="增加数量" v-model="stockForm.addAmount" />
            <button class="btn" @click="addStock" :disabled="!stockForm.dishId">增加库存</button>
            
            <!-- 显示当前库存 -->
            <view v-if="selectedDish" class="stock-info">
                当前库存: {{ selectedDish.currentStock }}
            </view>
        </view>

        <view class="admin-card">
            <view class="card-title">4. 删除评价</view>
            <!-- 改为选择器 -->
            <picker mode="selector" :range="reviewList" range-key="displayText" @change="onReviewChange">
                <view class="input picker-input">
                    {{ reviewId ? selectedReviewText : '请选择要删除的评价' }}
                </view>
            </picker>
            
            <!-- 显示评价详情 -->
            <view v-if="selectedReview" class="review-detail">
                <view>用户ID: {{ selectedReview.userId }}</view>
                <view>评分: {{ selectedReview.rating }}星</view>
                <view>内容: {{ selectedReview.comment }}</view>
                <view>时间: {{ formatDate(selectedReview.createdAt) }}</view>
            </view>
            
            <button class="btn btn-red" @click="deleteReview" :disabled="!reviewId">强制删除评价</button>
        </view>
        
        <!-- 数据刷新按钮 -->
        <view class="refresh-section">
            <button class="btn-refresh" @click="loadAllData">🔄 刷新数据</button>
        </view>
    </view>
</template>

<script>
import { request } from '@/utils/request.js';

export default {
    data() {
        return {
            resForm: { name: '', address: '' },
            dishForm: { restaurantId: '', name: '', price: '', currentStock: '' },
            stockForm: { dishId: '', addAmount: '', restaurantId: '' },
            reviewId: '',
            
            // 列表数据
            restaurantList: [],
            allDishList: [],
            reviewList: [],
            
            // 选中项缓存
            selectedRestaurant: null,
            selectedDish: null,
            selectedReview: null
        }
    },
    
    computed: {
        // 根据选中的店铺筛选菜品
        filteredDishList() {
            if (!this.stockForm.restaurantId) return [];
            return this.allDishList.filter(dish => 
                dish.restaurantId === this.stockForm.restaurantId
            );
        },
        
        // 显示用的名称
        selectedRestaurantName() {
            const r = this.restaurantList.find(r => r.restaurantId === this.dishForm.restaurantId);
            return r ? `${r.name} (${r.restaurantId.substring(0, 8)}...)` : '';
        },
        
        stockRestaurantName() {
            const r = this.restaurantList.find(r => r.restaurantId === this.stockForm.restaurantId);
            return r ? `${r.name} (${r.restaurantId.substring(0, 8)}...)` : '';
        },
        
        selectedDishName() {
            const d = this.allDishList.find(d => d.dishId === this.stockForm.dishId);
            return d ? `${d.name} (¥${d.price})` : '';
        },
        
        selectedReviewText() {
            if (!this.selectedReview) return '';
            const shortId = this.reviewId.substring(0, 8);
            return `${this.selectedReview.comment?.substring(0, 20)}`;
        }
    },
    
    onLoad() {
        this.loadAllData();
    },
    
    methods: {
        // 加载所有需要的数据
        async loadAllData() {
            try {
                uni.showLoading({ title: '加载数据中...' });
                
                // 并行加载所有数据
                const [restaurants, dishes, reviews] = await Promise.all([
                    request({ url: '/api/admin/restaurants/list', method: 'GET' }),
                    request({ url: '/api/admin/dishes/list', method: 'GET' }),
                    request({ url: '/api/admin/reviews/list', method: 'GET' })
                ]);
                
                this.restaurantList = restaurants || [];
                this.allDishList = dishes || [];
                
                // 处理评价列表，添加显示文本
                this.reviewList = (reviews || []).map(review => ({
                    ...review,
                    displayText: `${review.comment.substring(0, 8)}... | ${review.rating}星`
									}));
                
                uni.hideLoading();
                uni.showToast({ title: '数据已更新', icon: 'success' });
            } catch (error) {
                uni.hideLoading();
                uni.showToast({ title: '加载失败', icon: 'error' });
                console.error('加载数据失败:', error);
            }
        },
        
        // 添加店铺
        async addRestaurant() {
            if (!this.resForm.name || !this.resForm.address) {
                uni.showToast({ title: '请填写完整信息', icon: 'none' });
                return;
            }
            
            try {
                await request({ url: '/api/admin/restaurants', method: 'POST', data: this.resForm });
                uni.showToast({ title: '添加店铺成功' });
                this.resForm = { name: '', address: '' };
                // 刷新店铺列表
                setTimeout(() => this.loadAllData(), 500);
            } catch (error) {
                uni.showToast({ title: '添加失败', icon: 'error' });
            }
        },
        
        // 添加菜品
        async addDish() {
            if (!this.dishForm.restaurantId || !this.dishForm.name || !this.dishForm.price) {
                uni.showToast({ title: '请填写完整信息', icon: 'none' });
                return;
            }
            
            try {
                await request({ url: '/api/admin/dishes', method: 'POST', data: this.dishForm });
                uni.showToast({ title: '添加菜品成功' });
                this.dishForm = { restaurantId: '', name: '', price: '', currentStock: '' };
                // 刷新菜品列表
                setTimeout(() => this.loadAllData(), 500);
            } catch (error) {
                uni.showToast({ title: '添加失败', icon: 'error' });
            }
        },
        
        // 增加库存
        async addStock() {
            if (!this.stockForm.dishId || !this.stockForm.addAmount) {
                uni.showToast({ title: '请选择菜品和数量', icon: 'none' });
                return;
            }
            
            try {
                await request({ 
                    url: `/api/admin/dishes/${this.stockForm.dishId}/stock?addAmount=${this.stockForm.addAmount}`, 
                    method: 'PUT' 
                });
                uni.showToast({ title: '库存增加成功' });
                this.stockForm.addAmount = '';
                // 刷新数据
                setTimeout(() => this.loadAllData(), 500);
            } catch (error) {
                uni.showToast({ title: '操作失败', icon: 'error' });
            }
        },
        
        // 删除评价
        async deleteReview() {
            if (!this.reviewId) {
                uni.showToast({ title: '请选择评价', icon: 'none' });
                return;
            }
            
            uni.showModal({
                title: '确认删除',
                content: '确定要删除这条评价吗？此操作不可恢复。',
                success: async (res) => {
                    if (res.confirm) {
                        try {
                            await request({ 
                                url: `/api/admin/reviews/${this.reviewId}`, 
                                method: 'DELETE' 
                            });
                            uni.showToast({ title: '评价已删除' });
                            this.reviewId = '';
                            this.selectedReview = null;
                            // 刷新评价列表
                            setTimeout(() => this.loadAllData(), 500);
                        } catch (error) {
                            uni.showToast({ title: '删除失败', icon: 'error' });
                        }
                    }
                }
            });
        },
        
        // 选择器事件处理
        onRestaurantChange(e) {
            const index = e.detail.value;
            const restaurant = this.restaurantList[index];
            this.dishForm.restaurantId = restaurant.restaurantId;
            this.selectedRestaurant = restaurant;
        },
        
        onStockRestaurantChange(e) {
            const index = e.detail.value;
            const restaurant = this.restaurantList[index];
            this.stockForm.restaurantId = restaurant.restaurantId;
            this.stockForm.dishId = ''; // 重置菜品选择
            this.selectedDish = null;
        },
        
        onDishChange(e) {
            const index = e.detail.value;
            const dish = this.filteredDishList[index];
            this.stockForm.dishId = dish.dishId;
            this.selectedDish = dish;
        },
        
        onReviewChange(e) {
            const index = e.detail.value;
            const review = this.reviewList[index];
            this.reviewId = review.reviewId;
            this.selectedReview = review;
        },
        
        // 格式化日期
        formatDate(dateStr) {
            if (!dateStr) return '';
            const date = new Date(dateStr);
            return `${date.getFullYear()}-${(date.getMonth()+1).toString().padStart(2,'0')}-${date.getDate().toString().padStart(2,'0')}`;
        }
    }
}
</script>

<style>
.container { 
    padding: 15px; 
    background: #f0f2f5; 
    min-height: 100vh; 
}
.title { 
    font-size: 20px; 
    font-weight: bold; 
    margin-bottom: 20px; 
    text-align: center;
    color: #333;
}
.admin-card { 
    background: #fff; 
    padding: 15px; 
    border-radius: 12px; 
    margin-bottom: 15px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}
.card-title { 
    font-weight: bold; 
    margin-bottom: 15px; 
    border-left: 4px solid #007aff; 
    padding-left: 10px;
    font-size: 16px;
}
.input { 
    border: 1px solid #ddd; 
    padding: 12px; 
    border-radius: 8px; 
    margin-bottom: 12px; 
    font-size: 14px;
    background: #fff;
}
.picker-input {
    color: #333;
    position: relative;
}
.picker-input:after {
    content: '▼';
    position: absolute;
    right: 12px;
    top: 50%;
    transform: translateY(-50%);
    font-size: 10px;
    color: #999;
}
.input.disabled {
    background: #f5f5f5;
    color: #999;
}
.btn { 
    background: linear-gradient(135deg, #007aff, #0056cc);
    color: #fff; 
    font-size: 14px;
    border-radius: 8px;
    height: 44px;
    line-height: 44px;
    margin-top: 5px;
}
.btn[disabled] {
    background: #ccc;
    color: #666;
}
.btn-red { 
    background: linear-gradient(135deg, #e53935, #c62828);
}
.stock-info {
    margin-top: 10px;
    padding: 10px;
    background: #e8f5e9;
    border-radius: 6px;
    font-size: 13px;
    color: #2e7d32;
}
.review-detail {
    margin: 10px 0;
    padding: 12px;
    background: #f8f9fa;
    border-radius: 8px;
    font-size: 12px;
    color: #666;
    line-height: 1.6;
}
.refresh-section {
    margin-top: 20px;
    padding: 0 15px;
}
.btn-refresh {
    background: #34c759;
    color: white;
    border-radius: 20px;
    font-size: 14px;
    height: 40px;
    line-height: 40px;
}
</style>