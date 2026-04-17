package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/*
* 此拦截器是第一层拦截器，因为并不是所有页面的访问都需要登录之后才能操作，所以要确保用户的每一个操作都能够刷新token
* 此拦截器的作用是需要获取token后判断用户是否存在并保存到ThreadLocal中，还要刷新token的有效期*/
public class RefreshTokenInterceptor implements HandlerInterceptor {

    StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;

    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        1、获取请求头中的token并判断token是否为空
        String token = request.getHeader("authorization");
        if (token == null){
            //        2、为空，放行
            return true;
        }
//        3、基于token获取redis中的用户
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//        4、判断用户是否存在
        if (userMap.isEmpty()) {
            //        5、不存在，放行
            return true;
        }
//        6、存在，将查询到的hash数据转化为UserDTO对象并保存到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
//        7、刷新token有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
//        8、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        移除用户避免内存泄漏
        UserHolder.removeUser();
    }
}
