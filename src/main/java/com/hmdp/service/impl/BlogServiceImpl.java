package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 1、根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 2、获取当前页数据
        List<Blog> records = page.getRecords();
        // 3、查询用户与点赞状态
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
//        1、查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
//        2、查询用户
        queryBlogUser(blog);
//        3、查看点赞状态
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
//        1、获取用户
        Long userId = UserHolder.getUser().getId();
//        2、判断用户点赞状态
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
//        3.1、未点赞，数据库点赞数+1，保存到redis中的sortedset
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
//        3.2、已点赞，数据库点赞数-1，从redis中的sortedset移除
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
//        1、查询前5点赞的用户
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5Id = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5Id == null || top5Id.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
//        2、解析用户id
        List<Long> ids = top5Id.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
//        3、根据用户id查询用户信息
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
//        4、返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
//        1、获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
//        2、保存博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增博文失败");
        }
//        4、查看笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
//        5、推送笔记id给粉丝
        for (Follow follow : follows) {
            // 获取粉丝id
            Long followUserId = follow.getUserId();
            String key = FEED_KEY + followUserId;
            //  推送
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
//        6、返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
//        1、获取当前用户
        Long userId = UserHolder.getUser().getId();
//        2、查询收件箱 ZREVRANGEBYSCORES key max min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2L);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
//        3、解析数据 blogId,minTime(时间戳),offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        int newOffset = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                newOffset++;
            } else {
                minTime = time;
                newOffset = 1;
            }
        }
//        4、根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();
        for (Blog blog : blogs){
//        4.1、查询用户
            queryBlogUser(blog);
//        4.2、查看点赞状态
            isBlogLiked(blog);
        }
//        5、封装返回blog
        ScrollResult r = new ScrollResult();
        r.setOffset(newOffset);
        r.setMinTime(minTime);
        r.setList(blogs);
        return Result.ok(r);
    }

    private void isBlogLiked(Blog blog) {
//        1、获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
//            用户未登录，无需判断用户的点赞状态
            return;
        }
        Long userId = UserHolder.getUser().getId();
//        2、判断用户点赞状态
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
