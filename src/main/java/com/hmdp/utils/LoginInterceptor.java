package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/*
* 此拦截器是第二层，作用就只是判断用户是否存在
* */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        判断ThreadLocal内是否有用户
        if (UserHolder.getUser() == null) {
            //        用户不存在，拦截
            response.setStatus(401);
            return false;
        }
//        用户存在，放行
        return true;
    }
}
