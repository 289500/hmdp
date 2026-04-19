package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

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
//        为什么将锁加在方法外而不是方法内：该方法使用事务注解，会在方法执行完后由 spring 进行提交
//            如果将锁加在方法内，会出现锁释放而方法还未执行完的情况，导致又一次出现线程并发的安全性问题
//            所以需要保证整个方案执行完后才释放锁
        synchronized (UserHolder.getUser().getId().toString().intern()) {
//            获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
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
