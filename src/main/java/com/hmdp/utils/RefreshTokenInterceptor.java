package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * token刷新拦截器
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取携带在请求头中的token
        String token = request.getHeader("authorization");
        if (token==null){
            return true;
        }
        //2.从redis中找出tokenKey
        String tokenKey = "login:user" + token;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (map.isEmpty()){
            return true;
        }
        //3.刷新token的时长
        stringRedisTemplate.expire(tokenKey,30, TimeUnit.MINUTES);
        //将user对象存入ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
