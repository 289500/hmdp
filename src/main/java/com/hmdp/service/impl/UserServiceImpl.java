package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1、校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //        2、手机号格式有误
            return Result.fail("手机号格式有误");
        }
//        3、生成验证码
        String code = RandomUtil.randomNumbers(6);
//        4、保存验证码
        session.setAttribute("code", code);
//        5、发送验证码
        log.debug("验证码：{}", code);
//        6、返回成功
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        1、校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //        2、手机号格式有误
            return Result.fail("手机号格式有误");
        }
//        3、校验验证码
        Object CacheCode = session.getAttribute("code");
        if (CacheCode == null || !CacheCode.toString().equals(loginForm.getCode())){
//            4、验证码格式有误
            return Result.fail("验证码格式有误");
        }
//        5、查询用户是否存在
        User user = query().eq("phone", phone).one();
        if (user == null) {
//            6、用户不存在，创建新用户
            user = CreateUserWithPhone(phone);
        }
//        7、用户存在，将其转换为UserDTO后保存到session，UserDTO内没有敏感信息并且避免返回的东西太多导致内存压力过大
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User CreateUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
