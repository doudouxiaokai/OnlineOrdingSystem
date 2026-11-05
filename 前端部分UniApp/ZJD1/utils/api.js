import { request } from './request.js'

export const list_restaurants = () => {
  return request({ url: '/api/admin/restaurants/list', method: 'GET' })
}

export const list_dishes = (restaurant_id) => {
  return request({ url: `/api/admin/restaurants/${restaurant_id}/dishes`, method: 'GET' })
}

export function create_order(data) {
    return request({
        url: `/api/orders`,
        method: 'POST',
        data: data
    })
}

export const pay_order = (order_id, pay_method) => {
  return request({ url: '/api/orders/pay', method: 'POST', data: { order_id, pay_method } })
}

export const advance_order = (order_id) => {
  return request({ url: '/api/orders/advance', method: 'POST', data: { order_id } })
}

export const login = (phone) => {
  return request({ url: '/api/auth/login', method: 'POST', data: { phone } })
}

export const get_orders = (params) => {
  return request({ 
    url: '/api/orders/list', 
    method: 'GET', 
    params 
  })
}