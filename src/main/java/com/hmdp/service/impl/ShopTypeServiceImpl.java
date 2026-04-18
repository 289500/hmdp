package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
//        1、查询redis内的商铺类型数据
        String key = CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
//        2、判断是否命中
        List<ShopType> shopTypeList;
        if (StrUtil.isNotBlank(shopTypeJson)) {
//            3、redis命中，直接返回商铺类型
            shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
//        4、redis未命中，查询数据库
        shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null) {
//            5、数据库未命中，商铺类型不存在
            return Result.fail("店铺类型不存在");
        }
//        6、数据库命中，将信息写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
//        7、返回
        return Result.ok(shopTypeList);
    }
}
