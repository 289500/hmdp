package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1、校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //        2、手机号格式有误
            return Result.fail("手机号格式有误");
        }
//        3、生成验证码
        String code = RandomUtil.randomNumbers(6);
//        4、保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        5、发送验证码
        log.debug("验证码：{}", code);
//        6、返回成功
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        1、校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //        2、手机号格式有误
            return Result.fail("手机号格式有误");
        }
//        3、从redis中获取验证码并校验
        String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (CacheCode == null || !CacheCode.equals(loginForm.getCode())) {
//            4、验证码格式有误
            return Result.fail("验证码格式有误");
        }
//        5、查询用户是否存在
        User user = query().eq("phone", phone).one();
        if (user == null) {
//            6、用户不存在，创建新用户
            user = CreateUserWithPhone(phone);
        }
//        7、用户存在保存到redis中
//        7.1、随机生成token
        String token = UUID.randomUUID().toString(true);
//        7.2、将user转化为UserDTO放入hash中存储
//        UserDTO内的id是Long类型但stringRedisTemplate只能接收String类型的键值，直接使用会报错
//        所以需要新建一个里面的键值都为String类型的map
        Map<String, Object> userMap = BeanUtil.beanToMap(BeanUtil.copyProperties(user, UserDTO.class),
                new HashMap<>(),
                CopyOptions.create()
//                        忽略空值
                        .setIgnoreNullValue(true)
//                        将map内的键值转换为String类型的关键
                        .setFieldValueEditor((fieldKey, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//        7.3、设置token的有效期，防止用户数量过多导致内存压力
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
//        8、返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
//        1、获取用户信息
        Long userId = UserHolder.getUser().getId();
//        2、获取日期
        LocalDateTime now = LocalDateTime.now();
//        3、拼接用户信息与日期
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
//        4、获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
//        5、存入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
//        1、获取用户信息
        Long userId = UserHolder.getUser().getId();
//        2、获取日期
        LocalDateTime now = LocalDateTime.now();
//        3、拼接用户信息与日期
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
//        4、获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
//        5、获取本月截至今天为止的所有签到记录，返回的是十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有签到记录
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == 0) {
            return Result.ok(0);
        }
//        6、循环遍历
        int count = 0;
        while (true) {
//            6.1、让这个数字与1做与运算，得到数字的最后一个bit位并判断bit是否为0
            if ((num & 1) == 0) {
//                6.2、为0，未签到，结束
                break;
            } else {
//                6.3、不为0，签到，计数器+1
                count++;
            }
//            6.4、数字右移，继续判断下一个bit
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User CreateUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
