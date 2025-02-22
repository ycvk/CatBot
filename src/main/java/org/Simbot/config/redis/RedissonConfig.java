package org.Simbot.config.redis;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.Kryo5Codec;
import org.redisson.config.Config;
import org.redisson.config.TransportMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author ：ycvk
 * @description : Redisson配置
 * @date : 2023/08/26 13:27
 */
@Configuration
public class RedissonConfig {

    private final String osName = System.getProperty("os.name").toLowerCase();

    private enum OS {
        MAC, LINUX, WINDOWS
    }

    private OS getOperatingSystem() {
        if (osName.contains("mac")) {
            return OS.MAC;
        } else if (osName.contains("linux")) {
            return OS.LINUX;
        }
        return OS.WINDOWS;
    }

    @Bean
    public RedissonClient redissonClient() {
        final Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");

        config
//                .setCodec(new JsonJacksonCodec())//使用JsonJacksonCodec序列化 速度较慢 但是可以显示为json格式
                .setCodec(new Kryo5Codec())//使用Kryo5Codec序列化 速度更快 但是显示为二进制格式 占用内存更小
                .setTransportMode(setTransportMode())
                .setEventLoopGroup(setEventLoopGroup());

        return Redisson.create(config);
    }

    private TransportMode setTransportMode() {
        return switch (getOperatingSystem()) {
            case MAC -> TransportMode.KQUEUE;
            case LINUX -> TransportMode.EPOLL;
            default -> TransportMode.NIO;
        };
    }

    private EventLoopGroup setEventLoopGroup() {
        return switch (getOperatingSystem()) {
            case MAC -> new KQueueEventLoopGroup();
            case LINUX -> new EpollEventLoopGroup();
            default -> new NioEventLoopGroup();
        };
    }
}
