package com.online_ordering_system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.online_ordering_system.domain.Order;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository // 消除 OrderController 里的私有注入红线
public interface OrderMapper extends BaseMapper<Order> {
}
