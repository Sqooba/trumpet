package com.verisign.vscc.hdfs.trumpet.kafka;

import com.google.common.base.Preconditions;
import com.verisign.vscc.hdfs.trumpet.utils.TrumpetHelper;
import kafka.admin.AdminUtils;
import kafka.admin.RackAwareMode;
import kafka.message.Message;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.curator.framework.CuratorFramework;
import org.apache.kafka.common.security.JaasUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Kafka utility functions.
 * <p/>
 * The rational is to have only a dependency on Zookeeper
 * which we have anyway for the leader election
 * and then deduce the broker list from ZK.
 */
public class KafkaUtils {

    public static final int DEFAULT_NUM_OF_PARTITION = 1;
    public static final int DEFAULT_REPLICATION = 3;

    public static int DEFAULT_ZK_CONNECTION_TIMEOUT = 10000;
    public static int DEFAULT_ZK_SESSION_TIMEOUT = 10000;

    private static ZkUtils newZkUtils(CuratorFramework curatorFramework) {
        ZkConnection zkConnection = new ZkConnection(curatorFramework.getZookeeperClient().getCurrentConnectionString(), DEFAULT_ZK_SESSION_TIMEOUT);
        ZkClient zkClient1 = new ZkClient(zkConnection, DEFAULT_ZK_CONNECTION_TIMEOUT, ZKStringSerializer$.MODULE$);

        zkClient1.waitUntilConnected();
        return new ZkUtils(zkClient1, zkConnection, JaasUtils.isZkSecurityEnabled());
    }

    public static List<String> retrieveBrokerListFromZK(final CuratorFramework curatorFramework) throws Exception {
        final List<String> znodes = curatorFramework.getChildren().forPath(ZkUtils.BrokerIdsPath());
        final List<String> brokers = new ArrayList<>(znodes.size());
        for (String znode : znodes) {
            Map<String, Object> stringObjectMap = TrumpetHelper.toMap(curatorFramework.getData().forPath(ZkUtils.BrokerIdsPath() + "/" + znode));
            List<String> endpoints = (List<String>)stringObjectMap.get("endpoints");
            String endpoint = getEndpoint(endpoints, SimpleConsumerHelper.getSecurityProtocol());
            if (endpoint != null) {
                brokers.add(endpoint);
            }
        }
        return brokers;
    }

    public static void createTopic(String topic, CuratorFramework curatorFramework) {
        createTopic(topic, DEFAULT_NUM_OF_PARTITION, DEFAULT_REPLICATION, curatorFramework);
    }

    public static void createTopic(String topic, int partitions, int replication, CuratorFramework curatorFramework) {
        Preconditions.checkArgument(partitions > 0);
        Preconditions.checkArgument(replication > 0);
        ZkUtils zkUtils = null;

        try {
            zkUtils = newZkUtils(curatorFramework);
            AdminUtils.createTopic(zkUtils, topic, partitions, replication, new Properties(), RackAwareMode.Disabled$.MODULE$);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    public static boolean topicExists(String topic, CuratorFramework curatorFramework) {
        ZkUtils zkUtils = null;
        try {
            zkUtils = newZkUtils(curatorFramework);
            return AdminUtils.topicExists(zkUtils, topic);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    public static byte[] toByteArray(Message m) {
        ByteBuffer buf = m.payload();
        byte[] dst = new byte[buf.limit()];
        buf.get(dst);
        return dst;
    }

    public static String getEndpoint(List<String> endpoints, String securityProtocol) throws IOException {
        for (String endpoint: endpoints) {
            if (endpoint.startsWith(securityProtocol + "://")) {
                return endpoint.substring((securityProtocol + "://").length());
            }
        }
        return null;
    }
}
