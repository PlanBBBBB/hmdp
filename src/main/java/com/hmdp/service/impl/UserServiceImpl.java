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

import org.springframework.beans.BeanUtils;
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

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号格式错误
            return Result.fail("手机号不合法");
        }
        //3.生成随机验证码
        String code = RandomUtil.randomNumbers(6);

        //4.将验证码存入redis
        stringRedisTemplate.opsForValue().setIfAbsent("login:code:" + phone, code, 2, TimeUnit.MINUTES);

        //5.发送验证码（此处不发送，在控制台打印出来即可）
        log.debug("短信验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.验证手机号，能从redis中查找出来的用手机号作为的key一定是合法的，如果差不多则证明手机号不合法或前后手机号不一致
        String phone = loginForm.getPhone();
        Object code = stringRedisTemplate.opsForValue().get("login:code:" + phone);
        if (code == null) {
            return Result.fail("手机号不合法");
        }
        //2.验证验证码
        String userCode = loginForm.getCode();
        if (!userCode.equals(code)) {
            return Result.fail("验证码不正确");
        }

        //3.查询用户是否存在
        User user = query().eq("phone", phone).one();

        //4.用户不存在则注册新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        //5.将用户信息存入redis
        String token = UUID.randomUUID(true).toString(true);
        String tokenKey = "login:user" + token;
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        HashMap<String, String> userMap = new HashMap<>();
//        userMap.put("icon", userDTO.getIcon());
//        userMap.put("id", String.valueOf(userDTO.getId()));
//        userMap.put("nickName", userDTO.getNickName());
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置有效期
        stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }


    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        //设置手机号
        user.setPhone(phone);
        //设置昵称(默认名)，一个固定前缀+随机字符串
        user.setNickName("user_" + RandomUtil.randomString(8));
        //保存到数据库
        save(user);
        return user;
    }


    @Override
    public Result sign() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();
        //5. 写入Redis  BITSET key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }


    @Override
    public Result signCount() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();
        //5. 获取截止至今日的签到记录  BITFIELD key GET uDay 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //6. 循环遍历
        int count = 0;
        Long num = result.get(0);
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else
                count++;
            //数字右移，抛弃最后一位
            num >>>= 1;
        }
        return Result.ok(count);
    }

}
