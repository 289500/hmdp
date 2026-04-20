--1.参数列表
--1.1 优惠券 id
local voucherId = ARGV[1]
--1.2 用户id
local userId = ARGV[2]
--1.3 订单id
--local orderId = ARGV[3]

--2。数据 key
--2.1 库存
local stockKey = "seckill:stock:" .. voucherId
--2.2 订单
local orderKey = "seckill:order:" .. voucherId

--3.脚本业务
--3.1 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end
--3.2 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已下单
    return 2
end
--3.3 扣减库存
redis.call('incrby', stockKey, -1)
--3.4 返回订单
redis.call('sadd', orderKey, userId)
--3.5 发送消息到消息队列中
--redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0