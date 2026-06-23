package com.online_ordering_system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 激活定时任务
@MapperScan("com.online_ordering_system.mapper") // 扫描下方的Mapper接口
public class OnlineOrderingSystemApplication {


    public static void main(String[] args) {
        SpringApplication.run(OnlineOrderingSystemApplication.class, args);
    }

}
