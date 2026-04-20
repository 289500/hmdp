package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
//        1、查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        2、判断优惠券是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            未开始
            return Result.fail("秒杀活动尚未开始");
        }
//        3、判断优惠券是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            已结束
            return Result.fail("秒杀活动已结束");
        }
//        4、判断库存是否充足
        if (voucher.getStock() < 1) {
//            库存不足
            return Result.fail("库存不足");
        }
//        5、调用下单的方法
        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
        RLock lock = redissonClient.getLock("order:lock" + userId);
        boolean tryLock = lock.tryLock();
        if (!tryLock){
//            获取锁失败
            return Result.fail("不允许重复下单");
        }
        try {
//            获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
//        1、判断用户账户下是否有了订单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
//            用户已下单过，不允许重复下单
            return Result.fail("用户已下单过，不允许重复下单");
        }
//        2、扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
//        3、生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
//        3.1、生成订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
//        3.2、获取优惠券id
        voucherOrder.setVoucherId(voucherId);
//        3.3、获取用户id
        voucherOrder.setUserId(userId);

        save(voucherOrder);
//        4、返回订单id
        return Result.ok(orderId);
    }
}
