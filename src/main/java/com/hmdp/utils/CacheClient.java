package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough
            (String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
//        1、根据id在redis中查询信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        if (StrUtil.isNotBlank(json)) {
//            3、redis命中，直接返回信息
            return JSONUtil.toBean(json, type);
        }
//        上述的isNotBlank只是判断json内是否为空
//        缓存的空对象虽然是null但他本身并不是空，所以需要在这里再判断一步
        if (json != null) {
            return null;
        }
//        4、redis未命中，根据id查询数据库且判断是否存在
        R r = dbFallback.apply(id);
        if (r == null) {
//            5、数据库内没有该信息，给redis缓存空对象
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
//        6、数据库存在信息，将信息写入redis
        this.set(key, r, time, unit);
//        7、返回信息
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public  <R, ID> R queryWithLogicalExpire
            (String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
//        1、根据id在redis中查询信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        if (StrUtil.isBlank(json)) {
//            3、redis存在，返回空
            return null;
        }
//        4、redis命中，将json序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
//        5、判断是否超过了逻辑过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {
//            6、未过期，返回信息
            return r;
        }
//        7、过期，需要开启新的线程重建缓存
//        7.1、获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
//            7.2、获取锁成功，再次查询缓存内的过期时间是否正确
            if (expireTime.isAfter(LocalDateTime.now())) {
//            未过期，返回信息
                return r;
            }
//            7.3开启新的线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                        try {
//                            7.4、查数据库
                            R r1 = dbFallback.apply(id);
//                            7.5、写入redis
                            setWithLogicalExpire(key, r1, time, unit);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
//                7.4、释放锁
                            delKey(lockKey);
                        }
                    }
            );
        }
//        7.5、失败，返回信息
        return r;
    }

    /*获取锁*/
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        使用BooleanUtil防止拆箱后出现空指针
        return BooleanUtil.isTrue(flag);
    }

    /*释放锁*/
    private void delKey(String key) {
        stringRedisTemplate.delete(key);
    }

}
