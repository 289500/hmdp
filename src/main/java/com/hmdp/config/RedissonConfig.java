package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
//        配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.26.130:6379").setPassword("s2895004094");
//        创建 redissonClient 对象
        return Redisson.create(config);
    }
}
