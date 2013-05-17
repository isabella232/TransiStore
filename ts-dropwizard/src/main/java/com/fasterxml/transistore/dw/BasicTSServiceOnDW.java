package com.fasterxml.transistore.dw;

import java.util.List;

import com.yammer.dropwizard.config.Bootstrap;
import com.yammer.dropwizard.config.Environment;

import com.fasterxml.storemate.shared.TimeMaster;
import com.fasterxml.storemate.store.StorableStore;
import com.fasterxml.storemate.store.file.FileManager;
import com.fasterxml.storemate.store.file.FileManagerConfig;

import com.fasterxml.clustermate.dw.DWBasedService;
import com.fasterxml.clustermate.dw.HealthCheckForCluster;
import com.fasterxml.clustermate.dw.HealthCheckForStore;
import com.fasterxml.clustermate.service.SharedServiceStuff;
import com.fasterxml.clustermate.service.Stores;
import com.fasterxml.clustermate.service.cfg.ServiceConfig;
import com.fasterxml.clustermate.service.cleanup.CleanupTask;
import com.fasterxml.clustermate.service.cluster.ClusterViewByServer;
import com.fasterxml.clustermate.service.servlet.StoreEntryServlet;
import com.fasterxml.clustermate.service.store.StoreHandler;
import com.fasterxml.clustermate.service.store.StoredEntry;
import com.fasterxml.clustermate.service.store.StoredEntryConverter;
import com.fasterxml.clustermate.service.store.StoresImpl;

import com.fasterxml.transistore.basic.BasicTSKey;
import com.fasterxml.transistore.basic.BasicTSListItem;
import com.fasterxml.transistore.dw.cmd.*;
import com.fasterxml.transistore.service.SharedTSStuffImpl;
import com.fasterxml.transistore.service.cfg.BasicTSFileManager;
import com.fasterxml.transistore.service.cfg.BasicTSServiceConfig;
import com.fasterxml.transistore.service.cleanup.LastAccessCleaner;
import com.fasterxml.transistore.service.store.BasicTSStoreHandler;
import com.fasterxml.transistore.service.store.BasicTSStores;

/**
 * Main service class that sets up service configuration, bootstrapping things
 * and initializing life-cycle components and resources.
 */
public class BasicTSServiceOnDW
    extends DWBasedService<BasicTSKey, StoredEntry<BasicTSKey>, BasicTSListItem,
        BasicTSServiceConfig, BasicTSServiceConfigForDW>
{
    /*
    /**********************************************************************
    /* Construction
    /**********************************************************************
     */

    protected BasicTSServiceOnDW(TimeMaster timings) {
        this(timings, false);
    }

    protected BasicTSServiceOnDW(TimeMaster timings, boolean testMode) {
        super(timings, testMode);
        
    }

    @Override
    public void initialize(Bootstrap<BasicTSServiceConfigForDW> bootstrap) {
        super.initialize(bootstrap);
        // and FreeMarker for more dynamic pages?
//      addBundle(new com.yammer.dropwizard.views.ViewBundle());
        // Some basic commands that may prove useful
        bootstrap.addCommand(new CommandDumpBDB());
        bootstrap.addCommand(new CommandCleanBDB());
    }
    
    public static void main(String[] args) throws Exception
    {
        new BasicTSServiceOnDW(TimeMaster.nonTestInstance()).run(args);
    }
    
    /*
    /**********************************************************************
    /* Abstract method implementations from base class
    /**********************************************************************
     */

    @SuppressWarnings("unchecked")
    @Override
    protected StoredEntryConverter<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem> constructEntryConverter(BasicTSServiceConfig config,
            Environment environment) {
        return (StoredEntryConverter<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem>) config.getEntryConverter();
    }

    @Override
    protected FileManager constructFileManager(BasicTSServiceConfig serviceConfig)
    {
        return new BasicTSFileManager(
                new FileManagerConfig(serviceConfig.storeConfig.dataRootForFiles),
                _timeMaster);
    }

    @Override
    protected SharedServiceStuff constructServiceStuff(BasicTSServiceConfig serviceConfig,
            TimeMaster timeMaster, StoredEntryConverter<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem> entryConverter,
            FileManager files)
    {
        return new SharedTSStuffImpl(serviceConfig, timeMaster,
            entryConverter, files);
    }

    @Override
    protected StoresImpl<BasicTSKey, StoredEntry<BasicTSKey>> constructStores(SharedServiceStuff stuff,
            BasicTSServiceConfig serviceConfig, StorableStore store)
    {
        StoredEntryConverter<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem> entryConv = stuff.getEntryConverter();
        return new BasicTSStores(serviceConfig,
                _timeMaster, stuff.jsonMapper(), entryConv, store);
    }
    
    @Override
    protected StoreHandler<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem> constructStoreHandler(SharedServiceStuff serviceStuff,
            Stores<BasicTSKey, StoredEntry<BasicTSKey>> stores,
            ClusterViewByServer cluster) {
        // false -> no updating of last-accessed timestamps by default
        return new BasicTSStoreHandler(serviceStuff, _stores, cluster, false);
    }

    @Override
    protected StoreEntryServlet<BasicTSKey, StoredEntry<BasicTSKey>> constructStoreEntryServlet(SharedServiceStuff stuff,
            ClusterViewByServer cluster, StoreHandler<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem> storeHandler)
    {
        return new StoreEntryServlet<BasicTSKey, StoredEntry<BasicTSKey>>(stuff, _cluster, storeHandler);
    }

    /*
    /**********************************************************************
    /* Overrides
    /**********************************************************************
     */

    @Override
    protected void addHealthChecks(SharedServiceStuff stuff,
            Environment environment)
    {
        ServiceConfig config = stuff.getServiceConfig();
        environment.addHealthCheck(new HealthCheckForStore(config, _stores));
        environment.addHealthCheck(new HealthCheckForCluster(config, _cluster));
    }

    @Override
    protected List<CleanupTask<?>> constructCleanupTasks()
    {
        /* Default tasks (local file cleaner, local entry cleaner)
         * work as-is, but we also need to clean up last-accessed
         * entries:
         */
        List<CleanupTask<?>> tasks = super.constructCleanupTasks();
        tasks.add(new LastAccessCleaner());
        return tasks;
    }
    
    /*
    /**********************************************************************
    /* Extended API
    /**********************************************************************
     */

    public StoreHandler<BasicTSKey, StoredEntry<BasicTSKey>,BasicTSListItem> getStoreHandler() {
        return _storeHandler;
    }
}
