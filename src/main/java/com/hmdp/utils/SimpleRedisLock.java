package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String name;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /*尝试获取锁并添加过期时间*/
    @Override
    public boolean tryLock(long timeoutSec) {
//        获取当前线程的唯一标识用于记录值
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    /*释放锁*/
    @Override
    public void delKey() {
//        获取锁的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        获取线程的标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        if (threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}