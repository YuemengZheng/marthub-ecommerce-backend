package dev.yuemeng.marthub.auth;

import dev.yuemeng.marthub.config.MartHubProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final StringRedisTemplate redis; private final MartHubProperties props;
    public AuthService(StringRedisTemplate redis, MartHubProperties props){this.redis=redis;this.props=props;}
    public String login(long userId, String name){
        String token=UUID.randomUUID().toString().replace("-",""); String key=key(token);
        redis.opsForHash().putAll(key, Map.of("id",Long.toString(userId),"name",name));
        redis.expire(key, Duration.ofMinutes(props.getAuth().getTtlMinutes()));
        return token;
    }
    public SessionUser resolveAndRefresh(String token){
        if(token==null || token.isBlank()) return null;
        Map<Object,Object> values=redis.opsForHash().entries(key(token)); if(values.isEmpty()) return null;
        redis.expire(key(token), Duration.ofMinutes(props.getAuth().getTtlMinutes()));
        return new SessionUser(Long.parseLong(values.get("id").toString()), values.get("name").toString());
    }
    private String key(String token){return "auth:token:"+token;}
}
