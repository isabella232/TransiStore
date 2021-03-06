package com.fasterxml.transistore.clustertest.base.dual;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Assert;
import org.skife.config.TimeSpan;

import com.fasterxml.storemate.shared.IpAndPort;
import com.fasterxml.storemate.store.StorableStore;

import com.fasterxml.clustermate.dw.RunMode;
import com.fasterxml.clustermate.service.cfg.ClusterConfig;

import com.fasterxml.transistore.basic.BasicTSKey;
import com.fasterxml.transistore.clustertest.ClusterTestBase;
import com.fasterxml.transistore.clustertest.StoreForTests;
import com.fasterxml.transistore.clustertest.util.FakeHttpRequest;
import com.fasterxml.transistore.clustertest.util.FakeHttpResponse;
import com.fasterxml.transistore.clustertest.util.TimeMasterForClusterTesting;
import com.fasterxml.transistore.dw.BasicTSServiceConfigForDW;

import io.dropwizard.util.Duration;

/**
 * Tests for verifying that [Issue#25] is implemented: that is, if
 * clients manage to concurrently PUT different data for same key,
 * that cluster is able to resolve the conflict so that cluster
 * will only eventually have a single piece of content associated
 * with the key.
 */
public abstract class TwoNodesConflictTestBase extends ClusterTestBase
{
    final static int PORT_BASE = PORT_BASE_DUAL + PORT_DELTA_CONFLICT;
    
    // use ports that differ from other tests, just to minimize chance of
    // collision
    private final static int TEST_PORT1 = PORT_BASE + 0;
    private final static int TEST_PORT2 = PORT_BASE + 1;
    
    /**
     * First a simple test to verify that newer (by comparing server-local timestamps)
     * entry wins: creationTime used.
     */
    public void testTimestampResolution() throws Exception
    {
        initTestLogging(); // reduce noise

        IpAndPort endpoint1 = new IpAndPort("localhost:"+TEST_PORT1);
        IpAndPort endpoint2 = new IpAndPort("localhost:"+TEST_PORT2);

        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 360);
        BasicTSServiceConfigForDW serviceConfig1 = createTwoNodeConfig("fullStack2Conflict_1",
                TEST_PORT1, clusterConfig);
        
        // all nodes need same (or at least similar enough) cluster config:
        final long START_TIME = 1000L;
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(START_TIME);
        // important: last argument 'true' so that background sync thread gets started
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_FULL);
        BasicTSServiceConfigForDW serviceConfig2 = createTwoNodeConfig("fullStack2Conflict_2",
                TEST_PORT2, clusterConfig);
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_FULL);

        // Start up both
        startServices(service1, service2);

        final StorableStore entries1 = service1.getStoreHandler().getStores().getEntryStore();
        final StorableStore entries2 = service2.getStoreHandler().getStores().getEntryStore();
        
        try {
            final BasicTSKey KEY = contentKey("entry");

            // First: verify that stores are empty:
            assertEquals(0, entryCount(entries1));
            assertEquals(0, entryCount(entries2));

            FakeHttpResponse response = new FakeHttpResponse();
            service1.getStoreHandler().getEntry(new FakeHttpRequest(), response, KEY);
            assertEquals(404, response.getStatus());

            final byte[] DATA1 = "Data #1".getBytes("UTF-8");
            final byte[] DATA2 = "Second entry!".getBytes("UTF-8");
            
            // then try adding conflicting entries
            response = new FakeHttpResponse();
            service1.getStoreHandler().putEntry(new FakeHttpRequest(), response,
                    KEY, calcChecksum(DATA1), new ByteArrayInputStream(DATA1),
                    null, null, null);
            assertEquals(200, response.getStatus());
            
            // But make sure second is created bit later:
            timeMaster.advanceCurrentTimeMillis(90L); // -> at 1090           

            response = new FakeHttpResponse();
            service2.getStoreHandler().putEntry(new FakeHttpRequest(), response,
                    KEY, calcChecksum(DATA2), new ByteArrayInputStream(DATA2),
                    null, null, null);
            assertEquals(200, response.getStatus());

            assertEquals(1, entryCount(entries1));
            assertEquals(1, entryCount(entries2));

            // Ok: so we should have a conflict now, n'est pas? Double check...
            response = new FakeHttpResponse();
            service1.getStoreHandler().getEntry(new FakeHttpRequest(), response, KEY);
            assertEquals(200, response.getStatus());
            byte[] data = collectOutput(response);
            assertEquals(DATA1.length, data.length);
            Assert.assertArrayEquals(DATA1, data);

            response = new FakeHttpResponse();
            service2.getStoreHandler().getEntry(new FakeHttpRequest(), response, KEY);
            assertEquals(200, response.getStatus());
            data = collectOutput(response);
            assertEquals(DATA2.length, data.length);
            Assert.assertArrayEquals(DATA2, data);

            // And now... should reconcile I think. With grace period of at most 10 secs,
            // need to advance time a bit and all in all, may take a while so:

            final long start = System.currentTimeMillis();
            
            int round = 1;
            final int MAX_ROUNDS = 80; // 5 is enough from Eclipse, locally; but from CLI more is needed?
            timeMaster.advanceCurrentTimeMillis(12000L);
            Thread.sleep(50L);
            
            while (true) {
                assertEquals(1, entryCount(entries1));
                assertEquals(1, entryCount(entries2));
                
                response = new FakeHttpResponse();
                service2.getStoreHandler().getEntry(new FakeHttpRequest(), response, KEY);
                assertEquals(200, response.getStatus());
                data = collectOutput(response);
                if (data.length == DATA1.length) {
                    // looks good so far; verify
                    Assert.assertArrayEquals(DATA1, data);
                    if (round > 5) {
                        testWarn("Conflict resolved in %s rounds, %s msecs", round, System.currentTimeMillis()-start);
                    }
                    break;
                } else {
                    // but if not yet changed, ensure it's the original data...
                    assertEquals(DATA2.length, data.length);
                    Assert.assertArrayEquals(DATA2, data);
                }
                if (round >= MAX_ROUNDS) {
                    fail("Did not resolve conflict in "+round+" rounds");
                }
                ++round;
                timeMaster.advanceCurrentTimeMillis(1000L);
                Thread.sleep(25L);
            }
        } finally {
            service1.prepareForStop();
            service2.prepareForStop();

            timeMaster.advanceTimeToWakeAll();
            Thread.sleep(10L);

            service1._stop();
            service2._stop();
        }
        Thread.sleep(10L);
        service1.waitForStopped();
        service2.waitForStopped();
    }

    protected BasicTSServiceConfigForDW createTwoNodeConfig(String name, int port, ClusterConfig cluster)
            throws IOException
        {
            final Duration shutdownDelay = Duration.milliseconds(100L);
            return createNodeConfig(name, true, port, cluster)
                    .overrideShutdownGracePeriod(shutdownDelay)
                    .overrideSyncGracePeriod(new TimeSpan("1s"))
                    .disableRequestLog();
        }

}