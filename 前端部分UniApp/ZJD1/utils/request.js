const BASE_URL = 'https://frp-six.com:61910';

export const request = (options) => {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('token');

    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : ''
      },
      success: (res) => {
        if (res.statusCode === 200) {
          resolve(res.data);
        } else {
          // ✅ 关键：显示后端返回的业务错误
          uni.showToast({
            title: res.data?.message || '操作失败',
            icon: 'none'
          });
          reject(res.data);
        }
      },
      fail: () => {
        uni.showToast({
          title: '网络连接失败',
          icon: 'none'
        });
        reject();
      }
    });
  });
};