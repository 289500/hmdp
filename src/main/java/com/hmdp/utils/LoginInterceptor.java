package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        1、获取session中的用户
        Object user = request.getSession().getAttribute("user");
//        2、判断用户是否存在
        if (user == null) {
            //        3、不存在，拦截
            response.setStatus(401);
            return false;
        }
//        4、存在，保存用户到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
//        5、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        移除用户避免内存泄漏
        UserHolder.removeUser();
    }
}
