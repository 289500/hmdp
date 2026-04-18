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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
//        4、redis未命中，根据id查询数据库且判断是否存在
        Shop shop = getById(id);
        if (shop == null) {
//            5、数据库内没有该商铺信息
            return Result.fail("店铺不存在");
        }
//        6、数据库存在商铺信息，将信息写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
//        7、返回商铺信息
        return Result.ok(shop);
    }
}
