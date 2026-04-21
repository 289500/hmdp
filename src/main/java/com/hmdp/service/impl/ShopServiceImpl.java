package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
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
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        使用BooleanUtil防止拆箱后出现空指针
        return BooleanUtil.isTrue(flag);
    }

    /*释放锁*/
    private void delKey(String key) {
        stringRedisTemplate.delete(key);
    }

    @Transactional
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
//        1、判断是否需要按照坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            //返回数据
            return Result.ok(page.getRecords());
        }
//        2、计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
//        4、查询 redis，根据距离排序、分页，结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        // GEOSEARCH key BYLONLAT x, y BYRADIUS 10 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                //此处的 limit(end) 只能获取从 0 到 end 的数据，无法满足分页要求，所以要把结果获取出来后再手动获取从 from 开始的部分
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
//        5、解析 id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了
            return Result.ok(Collections.emptyList());
        }
        //截取 from ~ end 部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //获取店铺 id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取店铺距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
//        6、根据 id 查询店铺信息
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
//        7、返回
        return Result.ok(shops);
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
