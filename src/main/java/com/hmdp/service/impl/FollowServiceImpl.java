package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long followUserId) {
        //获取当前登录的userId
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        //查询当前用户是否关注了该笔记的博主
        queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId);
        //只查询一个count就行了
        int count = this.count(queryWrapper);
        return Result.ok(count > 0);
    }


    @Override
    public Result follow(Long followUserId, Boolean isFellow) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //判断是否关注
        if (isFellow) {
            //关注，则将信息保存到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            //如果保存成功
            boolean success = save(follow);
            //则将数据也写入Redis
            if (success) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //取关，则将数据从数据库中移除
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId);
            //如果取关成功
            boolean success = remove(queryWrapper);
            //则将数据也从Redis中移除
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + id;
        String key2 = "follows:" + userId;
        //对当前用户和博主用户的关注列表取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            //无交集就返回个空集合
            return Result.ok(Collections.emptyList());
        }
        //将结果转为list
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //之后根据ids去查询共同关注的用户，封装成UserDto再返回
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
