package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough
//                (CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_NULL_TTL, TimeUnit.MINUTES);

//        互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

//        逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire
                (CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /*解决缓存穿透：已使用封装的工具类替代*/
    private Shop queryWithPassThrough(Long id) {
//        1、根据id在redis中查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
//            3、redis命中，直接返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        上述的isNotBlank只是判断shopJson内是否为空
//        缓存的空对象虽然是null但他本身并不是空，所以需要在这里再判断一步
        if (shopJson != null) {
            return null;
        }
//        4、redis未命中，根据id查询数据库且判断是否存在
        Shop shop = getById(id);
        if (shop == null) {
//            5、数据库内没有该商铺信息，给redis缓存空对象
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
//        6、数据库存在商铺信息，将信息写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        7、返回商铺信息
        return shop;
    }

    /*互斥锁解决缓存击穿*/
    private Shop queryWithMutex(Long id) {
//        1、根据id在redis中查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
//            3、redis命中，直接返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        上述的isNotBlank只是判断shopJson内是否为空
//        缓存的空对象虽然是null但他本身并不是空，所以需要在这里再判断一步
        if (shopJson != null) {
            return null;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop;
        try {
//        4、redis未命中，先获取互斥锁再根据id查询数据库且判断是否存在
            if (!tryLock(lockKey)) {
                //            4.1、获取锁失败，休眠一段时间后再次查询数据
                Thread.sleep(50);
                return queryWithMutex(id);
            }
//        4.2、获取锁成功，先查询缓存防止前面已经有线程创建完毕
            String doubleCheck = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheck)) {
                return JSONUtil.toBean(doubleCheck, Shop.class);
            }
            if (doubleCheck != null) {
                return null;
            }
//        4.3、根据id查询数据库
            //模拟重建的延迟
            Thread.sleep(200);
            shop = getById(id);
            if (shop == null) {
                //            5、数据库内没有该商铺信息，给redis缓存空对象
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
//        6、数据库存在商铺信息，将信息写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
//        7、释放锁
            delKey(lockKey);
        }
//        8、返回商铺信息
        return shop;
    }

    /*逻辑过期解决缓存击穿：已使用封装的工具类替代*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Shop queryWithLogicalExpire(Long id) {
//        1、根据id在redis中查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        if (StrUtil.isBlank(shopJson)) {
//            3、redis存在，返回空
            return null;
        }
//        4、redis命中，将json序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
//        5、判断是否超过了逻辑过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {
//            6、未过期，返回商铺信息
            return shop;
        }
//        7、过期，需要开启新的线程重建缓存
//        7、1获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
//            7.2、获取锁成功，先查询当前缓存的逻辑过期时间是否正确
            if (expireTime.isAfter(LocalDateTime.now())) {
//            6、未过期，返回商铺信息
                return shop;
            }
//            7.3开启新的线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
//                7.4、释放锁
                    delKey(lockKey);
                }
            });
        }
//        7.5、失败，返回商铺信息
        return shop;
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

    @Override
    public Object update(Shop shop) {
//        1、判断店铺是否存在
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺不存在");
        }
//        2、更新数据库
        updateById(shop);
//        3、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }

    /*模拟将数据存入redis预热*/
    public void saveShop2Redis(Long id, Long expireTime) {
//        1、查询店铺数据
        Shop shop = getById(id);
//        2、设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
//        3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
