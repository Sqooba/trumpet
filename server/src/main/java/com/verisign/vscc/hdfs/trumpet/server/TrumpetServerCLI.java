package com.verisign.vscc.hdfs.trumpet.server;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.ScheduledReporter;
import com.verisign.vscc.hdfs.trumpet.AbstractAppLauncher;
import com.verisign.vscc.hdfs.trumpet.kafka.SimpleConsumerHelper;
import com.verisign.vscc.hdfs.trumpet.server.metrics.Metrics;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.util.ToolRunner;
import org.coursera.metrics.datadog.DatadogReporter;
import org.coursera.metrics.datadog.transport.UdpTransport;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TrumpetServerCLI extends AbstractAppLauncher {

    public static final String OPTION_DFS_EDITS_DIR = "dfs.edits.dir";
    public static final String OPTION_BASE_THROTTLE_TIME_MS = "base.throttle.time.ms";
    public static final String OPTION_KAFKA_REQUIRED_ACKS = "kafka.required.acks";
    public static final String OPTION_METRICS_PREFIX_DEFAULT = "trumpet." + getHostname();
    public static final String OPTION_STATSD_REPORTER_ENABLED = "statsd.enable";
    public static final String OPTION_STATSD_REPORTER_PREFIX = "statsd.prefix";

    private final CountDownLatch latch = new CountDownLatch(1);
    private boolean initialized = false;
    private TrumpetServer trumpetServer;
    private JmxReporter jmxReporter;
    private ScheduledReporter metricsReporter;

    private File dfsEditsDir;
    private long baseThrottleTimeMs;
    private int kafkaRequiredAcks;

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new TrumpetServerCLI(), args);
        System.exit(res);
    }

    @Override
    protected int internalRun() throws Exception {

        String tmpDfsEditsDir = null;
        if (getOptions().has(OPTION_DFS_EDITS_DIR)) {
            tmpDfsEditsDir = (String) getOptions().valueOf(OPTION_DFS_EDITS_DIR);
        } else {
            tmpDfsEditsDir = getConf().get(DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY);
            if (tmpDfsEditsDir != null) {
                final String[] dirs = tmpDfsEditsDir.split(",");
                final Path p = new Path(dirs[0]);
                tmpDfsEditsDir = p.toUri().getPath();
            }
        }
        if (tmpDfsEditsDir == null) {
            System.err.println("No " + OPTION_DFS_EDITS_DIR + " directory set. Please pass it to the command line with --"
                    + OPTION_DFS_EDITS_DIR + " or set " + DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY + " in your hadoop config");
            return ReturnCode.ERROR_WITH_DFS_EDIT_DIR;
        }

        dfsEditsDir = new File(tmpDfsEditsDir);
        if (!dfsEditsDir.isDirectory()) {
            System.err.println("Directory " + dfsEditsDir + " does not seem to exist. Please set the directory in your " +
                    "hadoop config (key is " + DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY + ")");
            return ReturnCode.ERROR_WITH_DFS_EDIT_DIR;
        }
        if (!dfsEditsDir.canRead()) {
            System.err.println("Directory " + dfsEditsDir + " can be read. Please correct the dfset the directory in your " +
                    "hadoop config (key is " + DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY + ")");
            return ReturnCode.ERROR_WITH_DFS_EDIT_DIR;
        }

        baseThrottleTimeMs = Long.parseLong((String) getOptions().valueOf(OPTION_BASE_THROTTLE_TIME_MS));
        kafkaRequiredAcks = Integer.parseInt((String) getOptions().valueOf(OPTION_KAFKA_REQUIRED_ACKS));

        boolean statsd = getOptions().has(OPTION_STATSD_REPORTER_ENABLED);

        final JmxReporter localJmxReporter = JmxReporter.forRegistry(Metrics.getRegistry()).build();
        jmxReporter = localJmxReporter;
        localJmxReporter.start();

        if (statsd) {

            // default prefix (trumpet.$(hostname --fqdn)) was meant for legacy Graphite reporter
            // if no prefix explicitly set as parameter, using a null prefix instead.
            String statsdPrefix = null;
            if (getOptions().has(OPTION_STATSD_REPORTER_PREFIX)) {
                statsdPrefix = (String) getOptions().valueOf(OPTION_STATSD_REPORTER_PREFIX);
            }

            EnumSet<DatadogReporter.Expansion> expansions = EnumSet.of(DatadogReporter.Expansion.COUNT,
                    DatadogReporter.Expansion.RATE_1_MINUTE,
                    DatadogReporter.Expansion.RATE_15_MINUTE,
                    DatadogReporter.Expansion.MEDIAN,
                    DatadogReporter.Expansion.P95,
                    DatadogReporter.Expansion.P99);

            UdpTransport udpTransport = new UdpTransport.Builder().build();
            metricsReporter = DatadogReporter.forRegistry(Metrics.getRegistry())
//                    .withEC2Host()
                    .withTransport(udpTransport)
//                    .withTags()
                    .withExpansions(expansions)
                    .withPrefix(statsdPrefix)
                    .build();

            metricsReporter.start(10, TimeUnit.SECONDS);
        }

        Metrics.uptime();

        final TrumpetServer localTrumpetServer = new TrumpetServer(getCuratorFrameworkUser(), getConf(), getTopic(), dfsEditsDir, kafkaRequiredAcks, baseThrottleTimeMs);
        trumpetServer = localTrumpetServer;
        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {

                System.out.println("Trumpet shutdown from external signal received.");

                try {
                    internalClose();
                } catch (IOException e) {
                    System.out.println("Exception while closing everythin. Cannot recover anyway, completing the shutdown process...");
                    e.printStackTrace();
                }
            }
        });

        initialized = true;

        System.out.println("Application is launching. Moving verbosity to log file.");

        localTrumpetServer.run();

        System.out.println("Application terminated. Shutdown complete.");

        return ReturnCode.ALL_GOOD;

    }

    @Override
    protected void initParser() {
        getParser().accepts(OPTION_DFS_EDITS_DIR, "Root directory where the edits files are stored " +
                "(i.e. without <serviceId>/current). Mandatory if " +
                DFSConfigKeys.DFS_JOURNALNODE_EDITS_DIR_KEY + " is not set in /etc/hadoop/conf/hdfs-site.xml.")
                .withRequiredArg();
        getParser().accepts(OPTION_BASE_THROTTLE_TIME_MS, "Min throttle time increment when no transactions are found. " +
                "The bigger value, the less resources will be used but the more time before events might be published.")
                .withRequiredArg().defaultsTo(String.valueOf(TrumpetServer.DEFAULT_BASE_THROTTLE_TIME_MS));
        getParser().accepts(OPTION_STATSD_REPORTER_ENABLED, "Enable statsd report to the local agent (port 8215)");
        getParser().accepts(OPTION_STATSD_REPORTER_PREFIX, "Prefix to add on the metrics reported to statsd, if enabled.")
                .withRequiredArg().defaultsTo(OPTION_METRICS_PREFIX_DEFAULT);
        getParser().accepts(OPTION_KAFKA_REQUIRED_ACKS, "Kafka request.required.acks property. 0=none (async), 1=leader, -1=all. See kafka documentation for more details.")
                .withRequiredArg().defaultsTo(String.valueOf(SimpleConsumerHelper.DEFAULT_REQUIRED_ACKS));
    }


    @Override
    protected void internalClose() throws IOException {
        latch.countDown();
        System.out.println("Trumpet shutdown internalClose");

        try {
            trumpetServer.close();
        } catch (IOException e) {
            // well, can't really recover here, shutting down anyway...
            e.printStackTrace();
        } finally {
            jmxReporter.stop();
            if (metricsReporter != null) {
                metricsReporter.stop();
            }
            Metrics.close();
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    protected class ReturnCode extends AbstractAppLauncher.ReturnCode {

        public static final int ERROR_WITH_DFS_EDIT_DIR = 5;

    }
}
