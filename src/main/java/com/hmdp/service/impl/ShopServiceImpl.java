package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        1、根据id在redis中查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
//            3、redis命中，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
//        上述的isNotBlank只是判断shopJson内是否为空
//        缓存的空对象虽然是null但他本身并不是空，所以需要在这里再判断一步
        if (shopJson != null){
            return Result.fail("店铺不存在");
        }
//        4、redis未命中，根据id查询数据库且判断是否存在
        Shop shop = getById(id);
        if (shop == null) {
//            5、数据库内没有该商铺信息，给redis缓存空对象
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
//        6、数据库存在商铺信息，将信息写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        7、返回商铺信息
        return Result.ok(shop);
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
}
