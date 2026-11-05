import { defineStore } from 'pinia';
import { ref, computed } from 'vue';

export const useCartStore = defineStore('cart', () => {
  const cartItems = ref([]);
  const currentRestaurantId = ref('');

  const totalAmount = computed(() => {
    return cartItems.value.reduce((sum, item) => sum + item.price * item.quantity, 0);
  });

  function addToCart(dish, restaurantId) {
    if (currentRestaurantId.value && currentRestaurantId.value !== restaurantId) {
      // 切换餐厅清空前一个购物车
      cartItems.value = [];
    }
    currentRestaurantId.value = restaurantId;
    
    const exist = cartItems.value.find(item => item.dishId === dish.dishId);
    if (exist) {
      exist.quantity++;
    } else {
      cartItems.value.push({ ...dish, quantity: 1 });
    }
  }

  function clearCart() {
    cartItems.value = [];
    currentRestaurantId.value = '';
  }

  return { cartItems, currentRestaurantId, totalAmount, addToCart, clearCart };
});
