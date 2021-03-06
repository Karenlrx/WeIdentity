package com.webank.weid.suite.persistence.redis;

import java.util.Arrays;
import java.util.List;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import com.webank.weid.constant.DataDriverConstant;
import com.webank.weid.util.PropertyUtils;

/**
 * redisson配置类.
 *
 * @author karenli 2020年7月3日
 */
public class RedissonConfig {

    private static final String redisurl = PropertyUtils.getProperty(
            DataDriverConstant.REDISSON_URL);

    public RedissonConfig() {
    }

    /**
     * 单节点模式.
     *
     * @return 返回单节点模式的redissonclient.
     */
    public RedissonClient redissonSingleClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisurl);
        RedissonClient client = Redisson.create(config);
        return client;
    }

    /**
     * 集群模式.
     *
     * @return 返回集群模式的redissonclient.
     */
    public RedissonClient redissonClusterClient() {

        Config config = new Config();
        //集群状态扫描间隔时间，单位是毫秒, 可以用"rediss://"来启用SSL连接
        try {
            config.useClusterServers().setScanInterval(DataDriverConstant.SCAN_INTERVAL)
                    .addNodeAddress(
                            redisurl.split(","))
                    .setMasterConnectionMinimumIdleSize(
                            DataDriverConstant.MASTER_CONNECTION_MINIMUM_IDLE_SIZE)
                    .setMasterConnectionPoolSize(
                            DataDriverConstant.MASTER_CONNECTION_POOL_SIZE)
                    .setMasterConnectionMinimumIdleSize(
                            DataDriverConstant.MASTER_CONNECTION_MINIMUM_IDLE_SIZE)
                    .setMasterConnectionPoolSize(
                            DataDriverConstant.MASTER_CONNECTION_POOL_SIZE)
                    .setConnectTimeout(
                            DataDriverConstant.CONNECT_TIMEOUT)
                    .setIdleConnectionTimeout(
                            DataDriverConstant.IDLE_CONNECTION_TIMEOUT)
                    .setTimeout(DataDriverConstant.TIMEOUT)
                    .setRetryAttempts(DataDriverConstant.RETRY_ATTEMPTS)
                    .setRetryInterval(DataDriverConstant.RETRY_INTERVAL);

        } catch (Exception e) {
            e.printStackTrace();
        }
        RedissonClient redisson = Redisson.create(config);
        return redisson;
    }

    /**
     * 判断redis配置为单节点模式还是集群模式.
     *
     * @return 返回集群模式/单节点模式的redissonclient.
     */
    public RedissonClient redismodelRecognition() {

        List<String> list = Arrays.asList(redisurl.split(","));
        if (list.size() > 1) {
            return redissonClusterClient();
        } else {
            return redissonSingleClient();
        }
    }
}
