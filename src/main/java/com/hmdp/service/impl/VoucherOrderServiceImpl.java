package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
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

    @Resource
    private IVoucherOrderService proxy;

    //初始化 lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//    注解含义：在当前类初始化之后开始执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }

    private class VoucherOrderHandle implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
//                    获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTask.take();
//                    创建订单
                    handleVoucherOrder(voucherOrder, proxy);
                } catch (Exception e){
                    log.error("订单创建错误", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder, IVoucherOrderService proxy) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("order:lock" + userId);
        boolean tryLock = lock.tryLock();
        if (!tryLock){
 //            获取锁失败
            log.error("不允许重复下单");
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
//        1、获取用户id
        Long userId = UserHolder.getUser().getId();
//        2、调用lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
//        3、判断脚本结果是否为0
//        3.1、不为0，没有下单资格
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不允许重复下单");
        }
//        3.2、为0，生成订单并将下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
//        生成订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
//        获取优惠券id
        voucherOrder.setVoucherId(voucherId);
//        获取用户id
        voucherOrder.setUserId(userId);
//        4、放入阻塞队列
        orderTask.add(voucherOrder);
//        5、返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
//        1、判断用户账户下是否有了订单
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
//            用户已下单过，不允许重复下单
            log.error("用户已下单过，不允许重复下单");
        }
//        2、扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
        }
//        3、生成订单
        save(voucherOrder);
    }

    /*未调用lua脚本的秒杀下单*/
//    @Override
//    public Result seckillVoucher(Long voucherId) {
// //        1、查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
// //        2、判断优惠券是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
// //            未开始
//            return Result.fail("秒杀活动尚未开始");
//        }
// //        3、判断优惠券是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
// //            已结束
//            return Result.fail("秒杀活动已结束");
//        }
// //        4、判断库存是否充足
//        if (voucher.getStock() < 1) {
// //            库存不足
//            return Result.fail("库存不足");
//        }
// //        5、调用下单的方法
//        Long userId = UserHolder.getUser().getId();
// //        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
//        RLock lock = redissonClient.getLock("order:lock" + userId);
//        boolean tryLock = lock.tryLock();
//        if (!tryLock){
// //            获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//        try {
// //            获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }
}
