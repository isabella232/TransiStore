package com.fasterxml.transistore.clustertest.base.dual;

import static org.junit.Assert.assertArrayEquals;

import java.io.*;
import java.util.Arrays;

import org.junit.Assert;

import io.dropwizard.util.Duration;

import com.fasterxml.storemate.shared.IpAndPort;
import com.fasterxml.storemate.shared.compress.Compression;
import com.fasterxml.storemate.shared.compress.Compressors;
import com.fasterxml.storemate.store.Storable;
import com.fasterxml.storemate.store.StoreOperationSource;

import com.fasterxml.clustermate.api.msg.ItemInfo;
import com.fasterxml.clustermate.client.NodesForKey;
import com.fasterxml.clustermate.client.call.ReadCallResult;
import com.fasterxml.clustermate.client.operation.DeleteOperationResult;
import com.fasterxml.clustermate.client.operation.InfoOperationResult;
import com.fasterxml.clustermate.client.operation.PutOperation;
import com.fasterxml.clustermate.client.operation.PutOperationResult;
import com.fasterxml.clustermate.dw.RunMode;
import com.fasterxml.clustermate.service.cfg.ClusterConfig;
import com.fasterxml.clustermate.service.cluster.ClusterPeer;

import com.fasterxml.transistore.basic.BasicTSKey;
import com.fasterxml.transistore.client.*;
import com.fasterxml.transistore.clustertest.ClusterTestBase;
import com.fasterxml.transistore.clustertest.StoreForTests;
import com.fasterxml.transistore.clustertest.util.TimeMasterForClusterTesting;
import com.fasterxml.transistore.dw.BasicTSServiceConfigForDW;

/**
 * Simple CRUD tests for two-node setup (with 100% overlapping key range),
 * using basic two-way replication by client. Both nodes run on same JVM.
 */
public abstract class TwoNodesSimpleTestBase extends ClusterTestBase
{
    final static int PORT_BASE = PORT_BASE_DUAL + PORT_DELTA_SIMPLE;

    final private int TEST_PORT1 = PORT_BASE + 0;
    final private int TEST_PORT2 = PORT_BASE + 1;

    final private IpAndPort endpoint1 = new IpAndPort("localhost:"+TEST_PORT1);
    final private IpAndPort endpoint2 = new IpAndPort("localhost:"+TEST_PORT2);
    
    // use ports that differ from other tests, just to minimize chance of
    // collision
    public void testSimpleTwoNode() throws Exception
    {
        initTestLogging(); // reduce noise

        // both nodes need same (or at least similar enough) cluster config:
        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 100);

        BasicTSServiceConfigForDW serviceConfig1 = createNodeConfig("fullStack2simple_1", true, TEST_PORT1, clusterConfig);
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(200L);
        
        // false -> don't bother with full init of background tasks:
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_MINIMAL);

        BasicTSServiceConfigForDW serviceConfig2 = createNodeConfig("fullStack2simple_2", true, TEST_PORT2, clusterConfig);
        serviceConfig2.getServiceConfig().cluster = clusterConfig;
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_MINIMAL);

        // then start both
        startServices(service1, service2);

        try {
            // require 2 OKs, to ensure both get the same data...
            BasicTSClientConfig clientConfig = new BasicTSClientConfigBuilder()
                    .setMinimalOksToSucceed(2)
                    .setOptimalOks(2)
                    .setMaxOks(2)
                    .setAllowRetries(false) // let's not give tests too much slack, shouldn't need it
                    .build();
            BasicTSClient client = createClient(clientConfig, endpoint1, endpoint2);
    
            // just for fun, use a space, slash and ampersand in key (to ensure correct encoding)
            final BasicTSKey KEY = contentKey("testSimple2/this&that/some item");
            
            // first: verify that we can do GET, but not find the entry:
            byte[] data = client.getContentAsBytes(null, KEY);
            assertNull("Should not yet have entry", data);
    
            // Then add said content
            final byte[] CONTENT = new byte[12000];
            Arrays.fill(CONTENT, (byte) 0xAC);
            PutOperationResult result = client.putContent(null, KEY, CONTENT)
                    .completeOptimally()
                    .finish();
            assertTrue(result.succeededOptimally());
            assertEquals(result.getSuccessCount(), 2);
    
            // find it; both with GET and HEAD
            data = client.getContentAsBytes(null, KEY);
            assertNotNull("Should now have the data", data);
            assertArrayEquals(CONTENT, data);
            long len = client.getContentLength(null, KEY);
            /* NOTE: should be getting uncompressed length, assuming we don't
             * claim we accept things as compresed (if we did, we'd get 48)
             */
            assertEquals(12000L, len);

            // Also: should be able to get ItemInfo:
            InfoOperationResult<ItemInfo> infoResult = client.findInfo(null, KEY);
            assertEquals(2, infoResult.getSuccessCount());
            assertEquals(0, infoResult.getFailCount());

            for (int i = 0; i < 2; ++i) {
                ReadCallResult<ItemInfo> infoWrapper = infoResult.get(i);
                assertNotNull(infoWrapper);
                ItemInfo info = infoWrapper.getResult();
                assertNotNull(info);
                assertEquals(CONTENT.length, info.getLength());
                verifyHash("content hash for entry #"+i, info.getHash(), CONTENT);
            }
            
            // delete:
            DeleteOperationResult del = client.deleteContent(null, KEY)
                    .completeOptimally()
                    .result();
            assertTrue(del.succeededMinimally());
            assertTrue(del.succeededOptimally());
            assertEquals(del.getSuccessCount(), 2);
    
            // after which content ... is no more:
            data = client.getContentAsBytes(null, KEY);
            assertNull("Should not have the data after DELETE", data);
        } finally {
            // and That's All, Folks!
            service1._stop();
            service2._stop();
            service1.waitForStopped();
            service2.waitForStopped();
        }
    }

    // Test case to verify that "straight to 2 copies" also works, not just partial
    public void testSimpleOptimalUpdate() throws Exception
    {
        initTestLogging();
        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 100);

        BasicTSServiceConfigForDW serviceConfig1 = createNodeConfig("fullStack2Optimal_1", true, TEST_PORT1, clusterConfig);
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(200L);
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_MINIMAL);

        BasicTSServiceConfigForDW serviceConfig2 = createNodeConfig("fullStack2Optimal_2", true, TEST_PORT2, clusterConfig);
        serviceConfig2.getServiceConfig().cluster = clusterConfig;
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_MINIMAL);
        startServices(service1, service2);

        try {
            BasicTSClientConfig clientConfig = new BasicTSClientConfigBuilder()
                    .setMinimalOksToSucceed(1)
                    .setOptimalOks(2)
                    .setMaxOks(2)
                    .setAllowRetries(false) // shouldnt be needed
                    .build();
            BasicTSClient client = createClient(clientConfig, endpoint1, endpoint2);
    
            final BasicTSKey KEY = contentKey("testSimple2/optimal/item");
            assertNull("Should not yet have entry", client.getContentAsBytes(null, KEY));

            final byte[] CONTENT = new byte[72000];
            Arrays.fill(CONTENT, (byte) 'O');
            PutOperation put = client.putContent(null, KEY, CONTENT).completeOptimally();
            assertTrue(put.result().succeededMinimally());
            assertTrue(put.result().succeededOptimally());
            put.finish();
            int successCount = put.result().getSuccessCount();
            assertTrue(put.result().succeededMaximally());
            assertEquals(2, successCount);
            assertEquals(0, put.result().getFailCount());
            assertEquals(0, put.result().getIgnoreCount());

            byte[] data = client.getContentAsBytes(null, KEY);
            assertNotNull("Should now have the data", data);
            assertArrayEquals(CONTENT, data);
        } finally {
            service1._stop();
            service2._stop();
            service1.waitForStopped();
            service2.waitForStopped();
        }
    }
    
    /**
     * Test to verify that it is possible to force a partial completion,
     * aimed at giving more control over concurrency setting.
     */
    public void testPartialOptimalUpdate() throws Exception
    {
        initTestLogging();
        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 100);

        BasicTSServiceConfigForDW serviceConfig1 = createNodeConfig("fullStack2Partial_1", true, TEST_PORT1, clusterConfig);
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(200L);
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_MINIMAL);

        BasicTSServiceConfigForDW serviceConfig2 = createNodeConfig("fullStack2Partial_2", true, TEST_PORT2, clusterConfig);
        serviceConfig2.getServiceConfig().cluster = clusterConfig;
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_MINIMAL);
        startServices(service1, service2);

        try {
            BasicTSClientConfig clientConfig = new BasicTSClientConfigBuilder()
                    .setMinimalOksToSucceed(1)
                    .setOptimalOks(2)
                    .setMaxOks(2)
                    .setAllowRetries(false) // shouldnt be needed
                    .build();
            BasicTSClient client = createClient(clientConfig, endpoint1, endpoint2);
    
            final BasicTSKey KEY = contentKey("testSimple2/partialOpt/item");

            assertNull("Should not yet have entry", client.getContentAsBytes(null, KEY));
    
            // Then add said content
            final byte[] CONTENT = new byte[9000];
            Arrays.fill(CONTENT, (byte) 'a');
            // but only request minimal completion
            PutOperation put = client.putContent(null, KEY, CONTENT).completeMinimally();
            assertTrue(put.result().succeededMinimally());
            int successCount = put.result().getSuccessCount();
            if (put.result().succeededOptimally()) {
                fail("Should not send full updates if only minimal match desired: success count = "+successCount);
            }
            assertFalse(put.result().succeededMaximally());
            assertEquals(1, successCount);
            // at this point, no declared fails, or skipped
            assertEquals(0, put.result().getFailCount());
            assertEquals(0, put.result().getIgnoreCount());

            // at first that is. And then complete
            PutOperationResult result = put.completeOptimally().finish();
            assertTrue(result.succeededMinimally());
            assertTrue(result.succeededOptimally());
            // note, since opt == max, this should also be true now
            assertTrue(result.succeededMaximally());
            assertEquals(result.getSuccessCount(), 2);
            assertEquals(0, result.getFailCount());
            assertEquals(0, result.getIgnoreCount());
            
            // find it; both with GET and HEAD
            byte[] data = client.getContentAsBytes(null, KEY);
            assertNotNull("Should now have the data", data);
            assertArrayEquals(CONTENT, data);
    
            // delete: also do incrementally
            DeleteOperationResult del = client.deleteContent(null, KEY)
                    .completeMinimally()
                    .result();
            assertTrue(del.succeededMinimally());
            assertFalse(del.succeededOptimally());
            assertEquals(del.getSuccessCount(), 1);

            // but should still have one copy
            byte[] b = client.getContentAsBytes(null, KEY);
            assertNotNull(b);
            assertEquals(data.length, b.length);
            
            // but then complete it
            del = client.deleteContent(null, KEY)
                    .completeOptimally()
                    .result();
            assertTrue(del.succeededOptimally());
            assertEquals(del.getSuccessCount(), 2);
            
            // after which content ... is no more:
            assertNull("Should not have the data after DELETE", client.getContentAsBytes(null, KEY));
        } finally {
            service1._stop();
            service2._stop();
            service1.waitForStopped();
            service2.waitForStopped();
        }
    }
    
    public void testPartialUpdateIncomplete() throws Exception
    {
        initTestLogging();
        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 100);

        BasicTSServiceConfigForDW serviceConfig1 = createNodeConfig("fullStack2Partial_1b", true, TEST_PORT1, clusterConfig);
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(200L);
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_MINIMAL);

        BasicTSServiceConfigForDW serviceConfig2 = createNodeConfig("fullStack2Partial_2b", true, TEST_PORT2, clusterConfig);
        serviceConfig2.getServiceConfig().cluster = clusterConfig;
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_MINIMAL);
        startServices(service1, service2);

        try {
            BasicTSClientConfig clientConfig = new BasicTSClientConfigBuilder()
                    .setMinimalOksToSucceed(1)
                    .setOptimalOks(1)
                    .setMaxOks(2)
                    .setAllowRetries(false)
                    .build();
            BasicTSClient client = createClient(clientConfig, endpoint1, endpoint2);
    
            final BasicTSKey KEY = contentKey("testSimple2/partialMax/item");
            assertNull("Should not yet have entry", client.getContentAsBytes(null, KEY));
            final byte[] CONTENT = new byte[1000];
            Arrays.fill(CONTENT, (byte) 'b');

            // This time, finish at minimal completion.
            PutOperation put = client.putContent(null, KEY, CONTENT)
                    .completeMinimally();
            PutOperationResult result = put.result();
            assertTrue(result.succeededMinimally());
            int successCount = result.getSuccessCount();
            assertTrue(result.succeededOptimally());
            if (result.succeededMaximally()) {
                fail("Should not send max updates if only minimal match desired: success count = "+successCount);
            }
            assertEquals(1, successCount);

            // First: before finishing, no known fails or skips
            assertEquals(0, result.getFailCount());
            assertEquals(0, result.getIgnoreCount());

            // but when finishing, should fill in the blanks
            result = put.finish();
            assertEquals(0, result.getFailCount());
            assertEquals(1, result.getIgnoreCount());
        } finally {
            service1._stop();
            service2._stop();
            service1.waitForStopped();
            service2.waitForStopped();
        }
    }

    public void testPartialMaximalUpdate() throws Exception
    {
        initTestLogging();
        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 100);

        BasicTSServiceConfigForDW serviceConfig1 = createNodeConfig("fullStack2Partial_1b", true, TEST_PORT1, clusterConfig);
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(200L);
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_MINIMAL);

        BasicTSServiceConfigForDW serviceConfig2 = createNodeConfig("fullStack2Partial_2b", true, TEST_PORT2, clusterConfig);
        serviceConfig2.getServiceConfig().cluster = clusterConfig;
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_MINIMAL);
        startServices(service1, service2);

        try {
            BasicTSClientConfig clientConfig = new BasicTSClientConfigBuilder()
                    .setMinimalOksToSucceed(1)
                    .setOptimalOks(1)
                    .setMaxOks(2)
                    .setAllowRetries(false)
                    .build();
            BasicTSClient client = createClient(clientConfig, endpoint1, endpoint2);
    
            final BasicTSKey KEY = contentKey("testSimple2/partialMax/item");
            assertNull("Should not yet have entry", client.getContentAsBytes(null, KEY));
            final byte[] CONTENT = new byte[1000];
            Arrays.fill(CONTENT, (byte) 'b');
            // but only request minimal completion (which here is same as optimal)
            PutOperation put = client.putContent(null, KEY, CONTENT).completeMinimally();
            assertTrue(put.result().succeededMinimally());
            int successCount = put.result().getSuccessCount();
            assertTrue(put.result().succeededOptimally());
            if (put.result().succeededMaximally()) {
                fail("Should not send max updates if only minimal match desired: success count = "+successCount);
            }
            assertEquals(1, successCount);
    
            // at first that is. But should get bonus round now...
            PutOperationResult result = client.putContent(null, KEY, CONTENT)
                    .tryCompleteMaximally()
                    .finish();
            assertTrue(result.succeededMinimally());
            assertTrue(result.succeededOptimally());
            assertTrue(result.succeededMaximally());
            assertEquals(result.getSuccessCount(), 2);
            
            // find it; both with GET and HEAD
            byte[] data = client.getContentAsBytes(null, KEY);
            assertNotNull("Should now have the data", data);
            assertArrayEquals(CONTENT, data);
    
            // delete:
            DeleteOperationResult del = client.deleteContent(null, KEY)
                    .completeOptimally()
                    .result();
            assertTrue(del.succeededMinimally());
            assertTrue(del.succeededOptimally());
            assertFalse(del.succeededMaximally());
            assertEquals(del.getSuccessCount(), 1);
            
            del = client.deleteContent(null, KEY)
                    .completeMaximally()
                    .result();
            assertTrue(del.succeededMaximally());
            assertEquals(del.getSuccessCount(), 2);
    
            // after which content ... is no more:
            assertNull("Should not have the data after DELETE", client.getContentAsBytes(null, KEY));
        } finally {
            service1._stop();
            service2._stop();
            service1.waitForStopped();
            service2.waitForStopped();
        }
    }
    
    /**
     * Unit test that sets up 2-node fully replicated cluster, updates only one host
     * with two entries, and ensures that sync happens correctly.
     */
    public void testTwoNodeSync() throws Exception
    {
        initTestLogging();
        ClusterConfig clusterConfig = twoNodeClusterConfig(endpoint1, endpoint2, 360);
        
        // reduce shutdown grace period to speed up shutdown...
        final Duration shutdownDelay = Duration.milliseconds(100L);
        BasicTSServiceConfigForDW serviceConfig1 = createNodeConfig("fullStack2b_1", true, TEST_PORT1, clusterConfig)
        		.overrideShutdownGracePeriod(shutdownDelay);
        // all nodes need same (or at least similar enough) cluster config:
        final long START_TIME = 200L;
        final TimeMasterForClusterTesting timeMaster = new TimeMasterForClusterTesting(START_TIME);
        // important: last argument 'true' so that background sync thread gets started
        StoreForTests service1 = StoreForTests.createTestService(serviceConfig1, timeMaster, RunMode.TEST_FULL);
        BasicTSServiceConfigForDW serviceConfig2 = createNodeConfig("fullStack2b_2", true, TEST_PORT2, clusterConfig)
        		.overrideShutdownGracePeriod(shutdownDelay);
        serviceConfig2.getServiceConfig().cluster = clusterConfig;
        StoreForTests service2 = StoreForTests.createTestService(serviceConfig2, timeMaster, RunMode.TEST_FULL);

        startServices(service1, service2);
        try {
            // only 1 ok so that we can verify syncing
            BasicTSClientConfig clientConfig = new BasicTSClientConfigBuilder()
                .setMinimalOksToSucceed(1)
                .setOptimalOks(1)
                .setMaxOks(1)
                .setAllowRetries(false) // let's not give tests too much slack, shouldn't need it
                .build();
            BasicTSClient client = createClient(clientConfig, endpoint1, endpoint2);
            /* at this point we can let server complete its initial startup,
             * which may include cleanup (test mode usually adds something like
             * 1 msec virtual sleep)
             */
            timeMaster.advanceCurrentTimeMillis(1L); // to 201
	
            // just for fun, use a space, slash and ampersand in key (to ensure correct encoding)
            final BasicTSKey KEY1 = contentKey("testSimple2a");
            final BasicTSKey KEY2 = contentKey("testSimple2b");
	
            // both keys should map to server2, so that we get imbalance
            final NodesForKey nodes1 = client.getCluster().getNodesFor(KEY1);
            assertEquals(2, nodes1.size());

            // we used keyspace length of 360, so:
            assertEquals("[180,+360]", nodes1.node(0).getActiveRange().toString());
            assertEquals(1530323550, service1.getKeyConverter().routingHashFor(KEY1));
	
            final NodesForKey nodes2 = client.getCluster().getNodesFor(KEY2);
            assertEquals(2, nodes2.size());
            assertEquals("[180,+360]", nodes2.node(0).getActiveRange().toString());
            assertEquals(937517304, service1.getKeyConverter().routingHashFor(KEY2));
            
            assertNotSame(service1.getEntryStore(), service2.getEntryStore());
            assertEquals(0, entryCount(service1.getEntryStore()));
            assertEquals(0, entryCount(service2.getEntryStore()));

            // Ok, so, add 2 entries on store 2:
	        
            final byte[] CONTENT1 = biggerSomewhatCompressibleData(128000);
            final byte[] CONTENT1_LZF = lzfCompress(CONTENT1);
            final byte[] CONTENT2 = biggerSomewhatCompressibleData(19000);
            final byte[] CONTENT2_LZF = lzfCompress(CONTENT2);
            PutOperationResult result = client.putContent(null, KEY1, CONTENT1)
                    .completeOptimally()
                    .finish();
            assertTrue(result.succeededOptimally());
            assertEquals(result.getSuccessCount(), 1);
            timeMaster.advanceCurrentTimeMillis(1L); // to 202
            result = client.putContent(null, KEY2, CONTENT2)
                    .completeOptimally()
                    .finish();
            assertTrue(result.succeededOptimally());
            assertEquals(result.getSuccessCount(), 1);
            
            assertEquals(0, entryCount(service1.getEntryStore()));
            assertEquals(2, entryCount(service2.getEntryStore()));

            // sanity check: verify that entry/entries were uploaded with full info
            Storable entry1 = service2.getEntryStore().findEntry(StoreOperationSource.REQUEST,
                    null, KEY1.asStorableKey());
            assertNotNull(entry1);
            assertEquals(CONTENT1.length, entry1.getOriginalLength());
            assertEquals(Compression.LZF, entry1.getCompression());
            assertEquals(CONTENT1_LZF.length, entry1.getStorageLength());
            File f = entry1.getExternalFile(service2.getFileManager());

            verifyHash("content hash for entry #1", entry1.getContentHash(), CONTENT1);
            byte[] b = readAll(f);
            verifyHash("compressed hash for entry #1", entry1.getCompressedHash(), readAll(f));
            byte[] b2 = Compressors.lzfUncompress(b);
            Assert.assertArrayEquals(CONTENT1, b2);

            assertEquals(START_TIME + 1L, entry1.getLastModified());

            Storable entry2 = service2.getEntryStore().findEntry(StoreOperationSource.REQUEST,
                    null, KEY2.asStorableKey());
            assertNotNull(entry2);
            assertEquals(CONTENT2.length, entry2.getOriginalLength());
            assertEquals(Compression.LZF, entry2.getCompression());
            assertEquals(CONTENT2_LZF.length, entry2.getStorageLength());

            f = entry2.getExternalFile(service2.getFileManager());
            verifyHash("content hash for entry #2", entry2.getContentHash(), CONTENT2);
            b = readAll(f);
            verifyHash("compressed hash for entry #1", entry2.getCompressedHash(), b);
            b2 = Compressors.lzfUncompress(b);
            Assert.assertArrayEquals(CONTENT2, b2);

            assertEquals(START_TIME + 2L, entry2.getLastModified());
            
            // verify they are visible from #2, not #1, 
            assertEquals(2, entryCount(service2.getEntryStore()));
            assertEquals("Should not have yet propagated entries to store #1",
	        		0, entryCount(service1.getEntryStore()));

            // ok. start syncing:
            timeMaster.advanceTimeToWakeAll();

            /* Looks like multiple sleep/resume cycles are needed to get things
             * to converge to steady state. Try to advance, sleep, a few times.
             */
            // FWIW, we seem to require about 7-8 rounds (!) to get it all done
            /*int rounds =*/ expectState("2/2", "Entry should be copied into first node", 5, 10,
                    service1, service2);
	
            // but wait! Let's verify that stuff is moved without corruption...
            Storable entryCopy1 = service1.getEntryStore().findEntry(StoreOperationSource.REQUEST,
                    null, KEY1.asStorableKey());
            assertNotNull(entryCopy1);
            assertEquals(entry1.getOriginalLength(), entryCopy1.getOriginalLength());
            assertEquals(entry1.getStorageLength(), entryCopy1.getStorageLength());
            assertTrue(entryCopy1.hasExternalData());
            f = entryCopy1.getExternalFile(service1.getFileManager());
            assertTrue(f.exists());
            assertEquals(entry1.getStorageLength(), f.length());
            assertEquals(entry1.getContentHash(), entryCopy1.getContentHash());
            assertEquals(entry1.getCompressedHash(), entryCopy1.getCompressedHash());

            Storable entryCopy2 = service1.getEntryStore().findEntry(StoreOperationSource.REQUEST,
                    null, KEY2.asStorableKey());
            assertNotNull(entryCopy2);
            assertEquals(entry2.getOriginalLength(), entryCopy2.getOriginalLength());
            assertEquals(entry2.getStorageLength(), entryCopy2.getStorageLength());
            assertEquals(entry2.getContentHash(), entryCopy2.getContentHash());
            assertEquals(entry2.getCompressedHash(), entryCopy2.getCompressedHash());

            // and finally, ensure that background sync threads did not encounter issues
            // ... but looks that might take a while, too
            for (int i = 0; ; ++i) {
                boolean fail = (i == 3);
                if (_verifyClusterPeer("service 1", service1, START_TIME+3L, fail)
                    && _verifyClusterPeer("service 2", service1, START_TIME+3L, fail)) {
                    break;
                }
                timeMaster.advanceCurrentTimeMillis(15000L);
                Thread.sleep(20L);
            }
            
        } finally {
            // and That's All, Folks!
            service1.prepareForStop();
            service2.prepareForStop();
            service1._stop();
            service2._stop();
        }
        try { Thread.sleep(20L); } catch (InterruptedException e) { }
        service1.waitForStopped();
        service2.waitForStopped();
    }

    protected boolean _verifyClusterPeer(String desc, StoreForTests store,
            long minSyncedUpTo, boolean fail)
    {
        for (ClusterPeer peer : store.getCluster().getPeers()) {
            if (peer.getFailCount() != 0) {
                if (fail) {
                    fail("Problem with "+desc+", peer for "+peer.getAddress()+" has "+peer.getFailCount()+" fails");
                }
                return false;
            }
            if (peer.getSyncedUpTo() < minSyncedUpTo) {
                if (fail) {
                    fail("Problem with "+desc+", peer for "+peer.getAddress()+" only synced up to "+peer.getSyncedUpTo()+", should have at least "+minSyncedUpTo);
                }
                return false;
            }
        }
        return true;
    }
}
