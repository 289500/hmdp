package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    2026-1-1 0:0:0的时间戳
    private static final Long BEGIN_SECOND = 1767225600L;

//    序列号位数
    private static final Long COUNTS_BITS = 32L;

    public long nextId(String keyPrefix){
//        1、生成时间戳
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timeTamp = nowSecond - BEGIN_SECOND;
//        2、生成序列号
//        2.1、获取当前日期，精确到天
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
//        2.2、自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
//        3、拼接并返回
        return timeTamp << COUNTS_BITS | count;
    }
}
