package com.online_ordering_system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.online_ordering_system.domain.Dish;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface DishMapper extends BaseMapper<Dish> {
}
