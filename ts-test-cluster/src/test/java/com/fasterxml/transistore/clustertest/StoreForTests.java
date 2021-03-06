package com.fasterxml.transistore.clustertest;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.server.Server;

import io.dropwizard.jetty.HttpConnectorFactory;
import io.dropwizard.server.SimpleServerFactory;
import io.dropwizard.setup.Environment;
import io.dropwizard.setup.Bootstrap;

import com.fasterxml.storemate.store.StorableStore;
import com.fasterxml.storemate.store.file.FileManager;
import com.fasterxml.clustermate.api.EntryKeyConverter;
import com.fasterxml.clustermate.dw.RunMode;
import com.fasterxml.clustermate.service.SharedServiceStuff;
import com.fasterxml.clustermate.service.cluster.ClusterViewByServer;
import com.fasterxml.transistore.basic.BasicTSKey;
import com.fasterxml.transistore.clustertest.util.TimeMasterForClusterTesting;
import com.fasterxml.transistore.dw.BasicTSServiceConfigForDW;
import com.fasterxml.transistore.dw.BasicTSServiceOnDW;

/**
 * Specialized sub-class of {@link BasicTSServiceOnDW} used by unit tests.
 */
public class StoreForTests extends BasicTSServiceOnDW
{
    /**
     * We keep type-safe reference to the <code>TimeMaster</code> 
     */
    protected final TimeMasterForClusterTesting _testTimer;

    protected final BasicTSServiceConfigForDW _serviceConfig;
    
    protected final AtomicBoolean _preStopped = new AtomicBoolean(false);

    protected Server _jettyServer;
    
    /*
    /**********************************************************************
    /* Construction
    /**********************************************************************
     */

    protected StoreForTests(BasicTSServiceConfigForDW cfg, TimeMasterForClusterTesting tm, RunMode mode) {
        super(tm, mode);
        _serviceConfig = cfg;
        _testTimer = tm;
    }

    /**
     * Method called by unit tests to set up a full HTTP service for testing
     * (either for stand-alone or cluster tests).
     * NOTE: <code>start()</code> method is not called; caller must do that.
     */
    public static StoreForTests createTestService(BasicTSServiceConfigForDW config,
            TimeMasterForClusterTesting timeMaster, RunMode mode)
        throws Exception
    {
        return new StoreForTests(config, timeMaster, mode);
    }

    public void startTestService() throws Exception
    {
        Bootstrap<BasicTSServiceConfigForDW> bootstrap = new Bootstrap<BasicTSServiceConfigForDW>(this);
        final Environment environment = new Environment("TestService",
                bootstrap.getObjectMapper(), // or Jackson.newObjectMapper(),
                bootstrap.getValidatorFactory().getValidator(),
                bootstrap.getMetricRegistry(),
                bootstrap.getClassLoader());
        bootstrap.run(_serviceConfig, environment);
        run(_serviceConfig, environment);

        //bootstrap.runWithBundles(_serviceConfig, environment);
        
        // Simple-/DefaultServerFactory:
        SimpleServerFactory serverF = new SimpleServerFactory();
        // die stupid gzip filter die
        serverF.getGzipFilterFactory().setEnabled(false);
        serverF.setApplicationContextPath("/");
        ((HttpConnectorFactory) serverF.getConnector()).setPort(_serviceConfig.getApplicationPort());
        final Server server = serverF.build(environment);
        _jettyServer = server;
        server.start();
    }

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    @Override
    public void _stop() throws Exception
    {
        // Let's ensure there are no threads blocked on virtual timer
        long wait = _testTimer.advanceTimeToWakeAll();
        // and apply tiny bit of delay iff time was advanced:
        if (wait > 0L) {
            Thread.sleep(20L);
        }
        super._stop();
        if (_jettyServer == null) {
            System.err.println("WARNING: _jettyServer null on _stop(); can't shut down Jetty");
        } else {
            _jettyServer.stop();
        }
    }

    public void waitForStopped() throws Exception {
        _testTimer.advanceTimeToWakeAll();
//        _jettyServer.join();
    }

    public void prepareForStop()
    {
        if (!_preStopped.get()) {
            _preStopped.set(true);
            try {
                super._prepareForStop();
            } catch (Exception e) {
                System.err.printf("prepareForStop fail (%s): %s\n", e.getClass().getName(), e.getMessage());
            }
        }
    }
    
    /*
    /**********************************************************************
    /* Accessors
    /**********************************************************************
     */

    public SharedServiceStuff getServiceStuff() {
        return _serviceStuff;
    }

    public StorableStore getEntryStore() {
        return _stores.getEntryStore();
    }
    
    public EntryKeyConverter<BasicTSKey> getKeyConverter() {
        return getServiceStuff().getKeyConverter();
    }

    @Override
    public TimeMasterForClusterTesting getTimeMaster() {
        return _testTimer;
    }

    public ClusterViewByServer getCluster() {
        return _cluster;
    }

    public FileManager getFileManager() {
        return _serviceStuff.getFileManager();
    }
}
