package com.online_ordering_system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.online_ordering_system.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface UserMapper extends BaseMapper<User> {
}
