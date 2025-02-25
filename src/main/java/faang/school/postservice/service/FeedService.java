package faang.school.postservice.service;

import faang.school.postservice.client.UserServiceClient;
import faang.school.postservice.config.context.UserContext;
import faang.school.postservice.dto.FeedDto;
import faang.school.postservice.dto.PostDto;
import faang.school.postservice.dto.client.UserDto;
import faang.school.postservice.dto.redis.TimePostId;
import faang.school.postservice.mapper.redis.RedisPostMapper;
import faang.school.postservice.mapper.redis.RedisUserMapper;
import faang.school.postservice.model.redis.RedisFeed;
import faang.school.postservice.model.redis.RedisPost;
import faang.school.postservice.model.redis.RedisUser;
import faang.school.postservice.repository.redis.RedisFeedRepository;
import faang.school.postservice.repository.redis.RedisPostRepository;
import faang.school.postservice.repository.redis.RedisUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedService {

    private final RedisFeedRepository redisFeedRepository;
    private final RedisUserRepository redisUserRepository;
    private final RedisPostRepository redisPostRepository;
    private final UserContext userContext;
    private final UserServiceClient userServiceClient;
    private final PostService postService;
    private final RedisUserMapper redisUserMapper;
    private final RedisPostMapper redisPostMapper;

    @Value("${post.feed.batch-size}")
    private Integer postsBatchSize;
    @Value("${post.feed.feed-size}")
    private Integer feedBatchSize;

    public List<FeedDto> getFeed(Long postId) {
        Long userId = userContext.getUserId();
        Optional<RedisFeed> redisFeed = redisFeedRepository.findById(userId);

        if (redisFeed.isEmpty()) {
            log.info("Posts from database was delivered to user {}", userId);
            return getPostsFromDB(userId, postId);
        }

        RedisFeed feed = redisFeed.get();
        List<Long> nextTwentyPostIds = getNextPosts(postId, feed);

        if (nextTwentyPostIds.isEmpty()) {
            log.info("Posts from database was delivered to user {}", userId);
            return getPostsFromDB(userId, postId);
        }

        List<FeedDto> resultFeed = new ArrayList<>();
        for (Long id : nextTwentyPostIds) {
            RedisPost redisPost = getOrSavePostInCache(id);
            RedisUser redisUser = getOrSaveUserInCache(redisPost.getUserId());
            resultFeed.add(buildFeedDto(redisUser, redisPost));
        }
        log.info("News feed to user with id={} was taken from Redis, first post in the feed batch have id={}",
                feed.getUserId(), resultFeed.get(0).getPostId());
        return resultFeed;
    }

    public void heatFeed() {
        userServiceClient.getAllUsersWithKafka();
        log.info("Feed heating is started");
    }

    public void heatUserFeed(UserDto userDto) {
        List<Long> followeeIds = userDto.getFolloweeIds();
        List<PostDto> firstPostsForFeed = postService.getFirstPostsForFeed(followeeIds, feedBatchSize);

        firstPostsForFeed.forEach(postDto -> {
            if (!redisPostRepository.existsById(postDto.getId())) {
                redisPostRepository.save(redisPostMapper.toEntity(postDto));
            }
            getOrSaveUserInCache(postDto.getAuthorId());
        });
        if (redisFeedRepository.findById(userDto.getId()).isEmpty()) {
            List<TimePostId> list = firstPostsForFeed.stream().map(postDto -> TimePostId.builder()
                    .publishedAt(postDto.getPublishedAt())
                    .postId(postDto.getId())
                    .build()).toList();
            SortedSet<TimePostId> feed = new TreeSet<>(list);
            RedisFeed redisFeed = RedisFeed.builder()
                    .postsId(feed)
                    .userId(userDto.getId())
                    .build();
            redisFeedRepository.save(redisFeed);
        }
    }


    private List<Long> getNextPosts(Long postId, RedisFeed feed) {
        TreeSet<TimePostId> currentFeedPostIds;

        if (postId == null) {
            currentFeedPostIds = (TreeSet<TimePostId>) feed.getPostsId();
            Iterator<TimePostId> iterator = currentFeedPostIds.descendingIterator();
            return getPostsList(iterator);
        }
        RedisPost redisPost = getOrSavePostInCache(postId);
        TimePostId prevPostId = TimePostId.builder()
                .postId(postId)
                .publishedAt(redisPost.getPublishedAt())
                .build();

        if (feed.getPostsId().contains(prevPostId)) {
            currentFeedPostIds = (TreeSet<TimePostId>) feed.getPostsId().headSet(prevPostId);
            Iterator<TimePostId> iterator = currentFeedPostIds.descendingIterator();
            return getPostsList(iterator);
        }
        return new ArrayList<>();
    }

    private List<Long> getPostsList(Iterator<TimePostId> iterator) {
        List<Long> nextTwentyPostIds = new ArrayList<>(postsBatchSize);
        int count = 0;
        while (iterator.hasNext() && count < postsBatchSize) {
            nextTwentyPostIds.add(iterator.next().getPostId());
            count++;
        }
        return nextTwentyPostIds;
    }

    private List<FeedDto> getPostsFromDB(long userId, Long postId) {
        RedisUser user = getOrSaveUserInCache(userId);
        List<FeedDto> feedDtos;
        List<PostDto> feed;

        if (postId == null) {
            feed = postService.getFirstPostsForFeed(user.getFolloweeIds(), postsBatchSize);
        } else {
            LocalDateTime publishedAt = postService.getPost(postId).getPublishedAt();
            feed = postService.getNextPostsForFeed(user.getFolloweeIds(), publishedAt, postsBatchSize);
        }

        feedDtos = feed.stream()
                .map(redisPostMapper::toEntity)
                .map(redisPost -> {
                    RedisUser redisUser = getOrSaveUserInCache(redisPost.getUserId());
                    return buildFeedDto(redisUser, redisPost);
                }).toList();
        return feedDtos;
    }

    private RedisUser getOrSaveUserInCache(long userId) {
        return redisUserRepository.findById(userId).orElseGet(() -> {
            RedisUser user = redisUserMapper.toEntity(userServiceClient.getUser(userId));
            return redisUserRepository.save(user);
        });
    }

    private RedisPost getOrSavePostInCache(long postId) {
        return redisPostRepository.findById(postId).orElseGet(() -> {
            RedisPost post = redisPostMapper.toEntity(postService.getPost(postId));
            return redisPostRepository.save(post);
        });
    }

    private FeedDto buildFeedDto(RedisUser user, RedisPost post) {
        return FeedDto.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .pictureFileId(user.getPictureFileId())
                .postId(post.getId())
                .content(post.getContent())
                .likes(post.getLikes())
                .comments(post.getRedisComments())
                .publishedAt(post.getPublishedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
