/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map;

import com.hazelcast.client.ClientCommandHandler;
import com.hazelcast.cluster.ClusterService;
import com.hazelcast.config.ExecutorConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizeConfig;
import com.hazelcast.core.*;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.client.*;
import com.hazelcast.map.merge.*;
import com.hazelcast.map.proxy.DataMapProxy;
import com.hazelcast.map.proxy.ObjectMapProxy;
import com.hazelcast.map.tx.TxnMapProxy;
import com.hazelcast.map.wan.MapReplicationRemove;
import com.hazelcast.map.wan.MapReplicationUpdate;
import com.hazelcast.monitor.impl.LocalMapStatsImpl;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.ClassLoaderUtil;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.protocol.Command;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.nio.serialization.SerializationServiceImpl;
import com.hazelcast.partition.MigrationEndpoint;
import com.hazelcast.partition.PartitionInfo;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.impl.Index;
import com.hazelcast.query.impl.IndexService;
import com.hazelcast.query.impl.QueryEntry;
import com.hazelcast.query.impl.QueryResultEntryImpl;
import com.hazelcast.spi.*;
import com.hazelcast.spi.impl.EventServiceImpl;
import com.hazelcast.spi.impl.ResponseHandlerFactory;
import com.hazelcast.transaction.Transaction;
import com.hazelcast.util.ConcurrencyUtil;
import com.hazelcast.util.ConcurrencyUtil.ConstructorFunction;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.wan.WanReplicationEvent;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;


public class MapService implements ManagedService, MigrationAwareService, MembershipAwareService,
        TransactionalService, RemoteService, EventPublishingService<EventData, EntryListener>,
        ClientProtocolService, PostJoinAwareService, SplitBrainHandlerService, ReplicationSupportingService {

    public final static String SERVICE_NAME = "hz:impl:mapService";

    private final ILogger logger;
    private final NodeEngine nodeEngine;
    private final PartitionContainer[] partitionContainers;
    private final ConcurrentMap<String, MapContainer> mapContainers = new ConcurrentHashMap<String, MapContainer>();
    private final ConcurrentMap<String, NearCache> nearCacheMap = new ConcurrentHashMap<String, NearCache>();
    private final ConcurrentMap<ListenerKey, String> eventRegistrations = new ConcurrentHashMap<ListenerKey, String>();
    private final AtomicReference<List<Integer>> ownedPartitions;
    private final Map<String, MapMergePolicy> mergePolicyMap;

    public MapService(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        logger = nodeEngine.getLogger(MapService.class.getName());
        partitionContainers = new PartitionContainer[nodeEngine.getPartitionService().getPartitionCount()];
        ownedPartitions = new AtomicReference<List<Integer>>();
        mergePolicyMap = new ConcurrentHashMap<String, MapMergePolicy>();
        mergePolicyMap.put(PutIfAbsentMapMergePolicy.NAME, new PutIfAbsentMapMergePolicy());
        mergePolicyMap.put(HigherHitsMapMergePolicy.NAME, new HigherHitsMapMergePolicy());
        mergePolicyMap.put(PassThroughMergePolicy.NAME, new PassThroughMergePolicy());
        mergePolicyMap.put(LatestUpdateMapMergePolicy.NAME, new LatestUpdateMapMergePolicy());
    }

    private final ConcurrentMap<String, LocalMapStatsImpl> statsMap = new ConcurrentHashMap<String, LocalMapStatsImpl>(1000);
    private final ConcurrencyUtil.ConstructorFunction<String, LocalMapStatsImpl> localMapStatsConstructorFunction = new ConcurrencyUtil.ConstructorFunction<String, LocalMapStatsImpl>() {
        public LocalMapStatsImpl createNew(String key) {
            return new LocalMapStatsImpl();
        }
    };

    public LocalMapStatsImpl getLocalMapStatsImpl(String name) {
        return ConcurrencyUtil.getOrPutIfAbsent(statsMap, name, localMapStatsConstructorFunction);
    }

    public void init(final NodeEngine nodeEngine, Properties properties) {
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            partitionContainers[i] = new PartitionContainer(this, i);
        }
        nodeEngine.getExecutionService().scheduleAtFixedRate(new MapEvictTask(), 1, 1, TimeUnit.SECONDS);
    }

    private void initMap(String mapName) {
        getMapContainer(mapName);
    }

    public void reset() {
        final PartitionContainer[] containers = partitionContainers;
        for (PartitionContainer container : containers) {
            if (container != null) {
                container.clear();
            }
        }
        for (NearCache nearCache : nearCacheMap.values()) {
            nearCache.clear();
        }
    }

    public void shutdown() {
        flushMaps();
        destroyMapStores();
        final PartitionContainer[] containers = partitionContainers;
        for (int i = 0; i < containers.length; i++) {
            PartitionContainer container = containers[i];
            if (container != null) {
                container.clear();
            }
            containers[i] = null;
        }
        for (NearCache nearCache : nearCacheMap.values()) {
            nearCache.clear();
        }
        nearCacheMap.clear();
        mapContainers.clear();
        eventRegistrations.clear();
    }

    private void destroyMapStores() {
        for (MapContainer mapContainer : mapContainers.values()) {
            MapStore store = mapContainer.getStore();
            if (store != null && store instanceof MapLoaderLifecycleSupport) {
                ((MapLoaderLifecycleSupport) store).destroy();
            }
        }
    }

    private void flushMaps() {
        for (PartitionContainer partitionContainer : partitionContainers) {
            for (String mapName : mapContainers.keySet()) {
                partitionContainer.getRecordStore(mapName).flush(false);
            }
        }
    }

    private final ConstructorFunction<String, MapContainer> mapConstructor = new ConstructorFunction<String, MapContainer>() {
        public MapContainer createNew(String mapName) {
            return new MapContainer(mapName, nodeEngine.getConfig().getMapConfig(mapName), MapService.this);
        }
    };

    public Operation getPostJoinOperation() {
        PostJoinMapOperation o = new PostJoinMapOperation();
        for (MapContainer mapContainer : mapContainers.values()) {
            o.addMapIndex(mapContainer);
        }
        return o;
    }

    public Runnable prepareMergeRunnable() {
        Map<MapContainer, Collection<Record>> recordMap = new HashMap<MapContainer, Collection<Record>>(mapContainers.size());
        for (MapContainer mapContainer : mapContainers.values()) {
            for (int i = 0; i < nodeEngine.getPartitionService().getPartitionCount(); i++) {
                RecordStore recordStore = getPartitionContainer(i).getRecordStore(mapContainer.getName());
                // add your owned entries to the map so they will be merged
                if (nodeEngine.getPartitionService().getPartitionOwner(i).equals(nodeEngine.getClusterService().getThisAddress())) {
                    if (!recordMap.containsKey(mapContainer)) {
                        recordMap.put(mapContainer, new ArrayList<Record>());
                    }
                    recordMap.get(mapContainer).addAll(recordStore.getRecords().values());
                }
                // clear all records either owned or backup
                recordStore.reset();
            }
        }
        return new Merger(recordMap);
    }

    @Override
    public void onReplicationEvent(WanReplicationEvent replicationEvent) {
        Object eventObject = replicationEvent.getEventObject();
        if (eventObject instanceof MapReplicationUpdate) {
            MapReplicationUpdate replicationUpdate = (MapReplicationUpdate) eventObject;
            EntryView entryView = replicationUpdate.getEntryView();
            MapMergePolicy mergePolicy = replicationUpdate.getMergePolicy();
            MergeOperation operation = new MergeOperation(replicationUpdate.getMapName(), toData(entryView.getKey()), entryView, mergePolicy);
            try {
                int partitionId = nodeEngine.getPartitionService().getPartitionId(entryView.getKey());
                Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId).build();
                invocation.invoke().get();
            } catch (Throwable t) {
                ExceptionUtil.rethrow(t);
            }
        } else if (eventObject instanceof MapReplicationRemove) {
            MapReplicationRemove replicationRemove = (MapReplicationRemove) eventObject;

            DeleteOperation operation = new DeleteOperation(replicationRemove.getMapName(), toData(replicationRemove.getKey()));
            try {
                int partitionId = nodeEngine.getPartitionService().getPartitionId(replicationRemove.getKey());
                Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId).build();
                invocation.invoke().get();
            } catch (Throwable t) {
                ExceptionUtil.rethrow(t);
            }
        }
    }

    public MapMergePolicy getMergePolicy(String mergePolicyName) {
        MapMergePolicy mergePolicy = null;
        mergePolicy = mergePolicyMap.get(mergePolicyName);
        if(mergePolicy == null && mergePolicyName != null) {
            try {
                // check if user has entered custom class name instead of policy name
                mergePolicy = ClassLoaderUtil.newInstance(mergePolicyName);
                mergePolicyMap.put(mergePolicyName, mergePolicy);
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                ExceptionUtil.rethrow(e);
            }
        }
        if(mergePolicy == null) {
            return mergePolicyMap.get(MapConfig.DEFAULT_MAP_MERGE_POLICY);
        }
        return mergePolicy;
    }

    public class Merger implements Runnable {

        Map<MapContainer, Collection<Record>> recordMap;

        public Merger(Map<MapContainer, Collection<Record>> recordMap) {
            this.recordMap = recordMap;
        }

        public void run() {
            for (final MapContainer mapContainer : recordMap.keySet()) {
                Collection<Record> recordList = recordMap.get(mapContainer);
                String mergePolicyName = mapContainer.getMapConfig().getMergePolicy();
                MapMergePolicy mergePolicy = getMergePolicy(mergePolicyName);

                // todo number of records may be high. below can be optimized a many records can be send in single invocation
                for (final Record record : recordList) {
                    final MapMergePolicy finalMergePolicy = mergePolicy;
                    // todo too many submission. should submit them in subgroups
                    nodeEngine.getExecutionService().submit("hz:map-merge", new Runnable() {
                        public void run() {
                            SimpleEntryView entryView = new SimpleEntryView(record.getKey(), toData(record.getValue()), record);
                            MergeOperation operation = new MergeOperation(mapContainer.getName(), record.getKey(), entryView, finalMergePolicy);
                            try {
                                int partitionId = nodeEngine.getPartitionService().getPartitionId(record.getKey());
                                Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, partitionId).build();
                                invocation.invoke().get();
                            } catch (Throwable t) {
                                ExceptionUtil.rethrow(t);
                            }
                        }
                    });
                }
            }
        }

    }

    public static class PostJoinMapOperation extends AbstractOperation {
        private List<MapIndexInfo> lsMapIndexes = new LinkedList<MapIndexInfo>();

        public String getServiceName() {
            return SERVICE_NAME;
        }

        void addMapIndex(MapContainer mapContainer) {
            final IndexService indexService = mapContainer.getIndexService();
            if (indexService.hasIndex()) {
                MapIndexInfo mapIndexInfo = new MapIndexInfo(mapContainer.getName());
                for (Index index : indexService.getIndexes()) {
                    mapIndexInfo.addIndexInfo(index.getAttributeName(), index.isOrdered());
                }
                lsMapIndexes.add(mapIndexInfo);
            }
        }

        class MapIndexInfo implements DataSerializable {
            String mapName;
            private List<IndexInfo> lsIndexes = new LinkedList<IndexInfo>();

            public MapIndexInfo(String mapName) {
                this.mapName = mapName;
            }

            public MapIndexInfo() {
            }

            class IndexInfo implements DataSerializable {
                String attributeName;
                boolean ordered;

                IndexInfo() {
                }

                IndexInfo(String attributeName, boolean ordered) {
                    this.attributeName = attributeName;
                    this.ordered = ordered;
                }

                public void writeData(ObjectDataOutput out) throws IOException {
                    out.writeUTF(attributeName);
                    out.writeBoolean(ordered);
                }

                public void readData(ObjectDataInput in) throws IOException {
                    attributeName = in.readUTF();
                    ordered = in.readBoolean();
                }
            }

            public void addIndexInfo(String attributeName, boolean ordered) {
                lsIndexes.add(new IndexInfo(attributeName, ordered));
            }

            public void writeData(ObjectDataOutput out) throws IOException {
                out.writeUTF(mapName);
                out.writeInt(lsIndexes.size());
                for (IndexInfo indexInfo : lsIndexes) {
                    indexInfo.writeData(out);
                }
            }

            public void readData(ObjectDataInput in) throws IOException {
                mapName = in.readUTF();
                int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    IndexInfo indexInfo = new IndexInfo();
                    indexInfo.readData(in);
                    lsIndexes.add(indexInfo);
                }
            }
        }

        @Override
        public void run() throws Exception {
            MapService mapService = getService();
            for (MapIndexInfo mapIndex : lsMapIndexes) {
                final MapContainer mapContainer = mapService.getMapContainer(mapIndex.mapName);
                final IndexService indexService = mapContainer.getIndexService();
                for (MapIndexInfo.IndexInfo indexInfo : mapIndex.lsIndexes) {
                    indexService.addOrGetIndex(indexInfo.attributeName, indexInfo.ordered);
                }
            }
        }

        @Override
        protected void writeInternal(ObjectDataOutput out) throws IOException {
            super.writeInternal(out);
            out.writeInt(lsMapIndexes.size());
            for (MapIndexInfo mapIndex : lsMapIndexes) {
                mapIndex.writeData(out);
            }
        }

        @Override
        protected void readInternal(ObjectDataInput in) throws IOException {
            super.readInternal(in);
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                MapIndexInfo mapIndexInfo = new MapIndexInfo();
                mapIndexInfo.readData(in);
                lsMapIndexes.add(mapIndexInfo);
            }
        }
    }

    public MapContainer getMapContainer(String mapName) {
        return ConcurrencyUtil.getOrPutSynchronized(mapContainers, mapName, mapContainers, mapConstructor);
    }

    public PartitionContainer getPartitionContainer(int partitionId) {
        return partitionContainers[partitionId];
    }

    public RecordStore getRecordStore(int partitionId, String mapName) {
        return getPartitionContainer(partitionId).getRecordStore(mapName);
    }

    public AtomicReference<List<Integer>> getOwnedPartitions() {
        if (ownedPartitions.get() == null) {
            ownedPartitions.set(nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress()));
        }
        return ownedPartitions;
    }

    public void beforeMigration(PartitionMigrationEvent event) {
    }

    public Operation prepareReplicationOperation(PartitionReplicationEvent event) {
        logger.log(Level.FINEST, "Preparing replication op -> " + event); // TODO: to FINEST
        final PartitionContainer container = partitionContainers[event.getPartitionId()];
        final MapReplicationOperation operation = new MapReplicationOperation(container, event.getPartitionId(), event.getReplicaIndex());
        return operation.isEmpty() ? null : operation;
    }

    public void commitMigration(PartitionMigrationEvent event) {
        logger.log(Level.FINEST, "Committing " + event);
        if (event.getMigrationEndpoint() == MigrationEndpoint.SOURCE) {
            migrateIndex(event);
            clearPartitionData(event.getPartitionId());
        } else {
            migrateIndex(event);
        }
        ownedPartitions.set(nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress()));
    }

    private void migrateIndex(PartitionMigrationEvent event) {
        final PartitionContainer container = partitionContainers[event.getPartitionId()];
        for (PartitionRecordStore mapPartition : container.maps.values()) {
            final MapContainer mapContainer = getMapContainer(mapPartition.name);
            final IndexService indexService = mapContainer.getIndexService();
            if (indexService.hasIndex()) {
                for (Record record : mapPartition.getRecords().values()) {
                    if (event.getMigrationEndpoint() == MigrationEndpoint.SOURCE) {
                        indexService.removeEntryIndex(record.getKey());
                    } else {
                        indexService.saveEntryIndex(new QueryEntry(getSerializationService(), record.getKey(), record.getKey(), record.getValue()));
                    }
                }
            }
        }
    }

    public void rollbackMigration(PartitionMigrationEvent event) {
        logger.log(Level.FINEST, "Rolling back " + event);
        if (event.getMigrationEndpoint() == MigrationEndpoint.DESTINATION) {
            clearPartitionData(event.getPartitionId());
        }
        ownedPartitions.set(nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress()));
    }

    private void clearPartitionData(final int partitionId) {
        logger.log(Level.FINEST, "Clearing partition data -> " + partitionId);   // TODO: to FINEST
        final PartitionContainer container = partitionContainers[partitionId];
        for (PartitionRecordStore mapPartition : container.maps.values()) {
            mapPartition.clear();
        }
        container.maps.clear();
    }

    public void clearPartitionReplica(int partitionId) {
        clearPartitionData(partitionId);
    }

    public Record createRecord(String name, Data dataKey, Object value, long ttl) {
        return createRecord(name, dataKey, value, ttl, false);
    }

    public Record createRecord(String name, Data dataKey, Object value, long ttl, boolean backup) {
        Record record = null;
        MapContainer mapContainer = getMapContainer(name);
        final MapConfig.InMemoryFormat inMemoryFormat = mapContainer.getMapConfig().getInMemoryFormat();
        boolean statisticsEnabled = mapContainer.getMapConfig().isStatisticsEnabled();
        if (inMemoryFormat == MapConfig.InMemoryFormat.BINARY) {
            record = new DataRecord(dataKey, toData(value), statisticsEnabled);
        } else if (inMemoryFormat == MapConfig.InMemoryFormat.OBJECT) {
            record = new ObjectRecord(dataKey, toObject(value), statisticsEnabled);
        } else if (inMemoryFormat == MapConfig.InMemoryFormat.CACHED) {
            record = new CachedDataRecord(dataKey, toData(value), statisticsEnabled);
        } else {
            throw new IllegalArgumentException("Should not happen!");
        }

        if (!backup) {
            if (ttl <= 0 && mapContainer.getMapConfig().getTimeToLiveSeconds() > 0) {
                record.getState().updateTtlExpireTime(mapContainer.getMapConfig().getTimeToLiveSeconds() * 1000);
                scheduleTtlEviction(name, dataKey, mapContainer.getMapConfig().getTimeToLiveSeconds() * 1000);
            }
            if (ttl > 0) {
                record.getState().updateTtlExpireTime(ttl);
                scheduleTtlEviction(name, record.getKey(), ttl);
            }
            if (mapContainer.getMapConfig().getMaxIdleSeconds() > 0) {
                record.getState().updateIdleExpireTime(mapContainer.getMapConfig().getMaxIdleSeconds() * 1000);
                scheduleIdleEviction(name, dataKey, mapContainer.getMapConfig().getMaxIdleSeconds() * 1000);
            }
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    public TxnMapProxy createTransactionalObject(Object id, Transaction transaction) {
        return new TxnMapProxy(String.valueOf(id), this, nodeEngine, transaction);
    }

    private final ConstructorFunction<String, NearCache> nearCacheConstructor = new ConstructorFunction<String, NearCache>() {
        public NearCache createNew(String mapName) {
            return new NearCache(mapName, MapService.this);
        }
    };

    private NearCache getNearCache(String mapName) {
        return ConcurrencyUtil.getOrPutIfAbsent(nearCacheMap, mapName, nearCacheConstructor);
    }

    public void putNearCache(String mapName, Data key, Data value) {
        NearCache nearCache = getNearCache(mapName);
        nearCache.put(key, value);
    }

    public void invalidateNearCache(String mapName, Data key) {
        NearCache nearCache = getNearCache(mapName);
        nearCache.invalidate(key);
    }

    public void invalidateAllNearCaches(String mapName, Data key) {
        InvalidateNearCacheOperation operation = new InvalidateNearCacheOperation(mapName, key);
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        for (MemberImpl member : members) {
            try {
                if (member.localMember())
                    continue;
                Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, member.getAddress()).build();
                invocation.invoke();
            } catch (Throwable throwable) {
                throw new HazelcastException(throwable);
            }
        }
        // below local invalidation is for the case the data is cached before partition is owned/migrated
        invalidateNearCache(mapName, key);
    }

    public Object getFromNearCache(String mapName, Data key) {
        NearCache nearCache = getNearCache(mapName);
        return nearCache.get(key);
    }

    public NodeEngine getNodeEngine() {
        return nodeEngine;
    }

    public String getServiceName() {
        return SERVICE_NAME;
    }

    public ObjectMapProxy createDistributedObject(Object objectId) {
        final String name = String.valueOf(objectId);
        initMap(name);
        return new ObjectMapProxy(name, this, nodeEngine);
    }

    public DataMapProxy createDistributedObjectForClient(Object objectId) {
        final String name = String.valueOf(objectId);
        initMap(name);
        return new DataMapProxy(name, this, nodeEngine);
    }

    public void destroyDistributedObject(Object objectId) {
        logger.log(Level.WARNING, "Destroying object: " + objectId);
        final String name = String.valueOf(objectId);
        mapContainers.remove(name);
        final PartitionContainer[] containers = partitionContainers;
        for (PartitionContainer container : containers) {
            if (container != null) {
                container.destroyMap(name);
            }
        }
        nodeEngine.getEventService().deregisterAllListeners(SERVICE_NAME, name);
    }

    public void memberAdded(final MembershipServiceEvent membershipEvent) {
    }

    public void memberRemoved(final MembershipServiceEvent membershipEvent) {
        MemberImpl member = membershipEvent.getMember();
        // TODO: @mm - when a member dies;
        // * rollback transaction
        // * do not know ?
    }

    public Map<Command, ClientCommandHandler> getCommandsAsMap() {
        Map<Command, ClientCommandHandler> commandHandlers = new HashMap<Command, ClientCommandHandler>();
        commandHandlers.put(Command.MGET, new MapGetHandler(this));
        commandHandlers.put(Command.MSIZE, new MapSizeHandler(this));
        commandHandlers.put(Command.MGETALL, new MapGetAllHandler(this));
        commandHandlers.put(Command.MPUT, new MapPutHandler(this));
        commandHandlers.put(Command.MTRYPUT, new MapTryPutHandler(this));
        commandHandlers.put(Command.MSET, new MapSetHandler(this));
        commandHandlers.put(Command.MPUTTRANSIENT, new MapPutTransientHandler(this));
        commandHandlers.put(Command.MLOCK, new MapLockHandler(this));
        commandHandlers.put(Command.MTRYLOCK, new MapTryLockHandler(this));
        commandHandlers.put(Command.MTRYREMOVE, new MapTryRemoveHandler(this));
        commandHandlers.put(Command.MISLOCKED, new MapIsLockedHandler(this));
        commandHandlers.put(Command.MUNLOCK, new MapUnlockHandler(this));
        commandHandlers.put(Command.MPUTALL, new MapPutAllHandler(this));
        commandHandlers.put(Command.MREMOVE, new MapRemoveHandler(this));
        commandHandlers.put(Command.MDELETE, new MapDeleteHandler(this));
        commandHandlers.put(Command.MCONTAINSKEY, new MapContainsKeyHandler(this));
        commandHandlers.put(Command.MCONTAINSVALUE, new MapContainsValueHandler(this));
        commandHandlers.put(Command.MPUTIFABSENT, new MapPutIfAbsentHandler(this));
        commandHandlers.put(Command.MREMOVEIFSAME, new MapRemoveIfSameHandler(this));
        commandHandlers.put(Command.MREPLACEIFNOTNULL, new MapReplaceIfNotNullHandler(this));
        commandHandlers.put(Command.MREPLACEIFSAME, new MapReplaceIfSameHandler(this));
        commandHandlers.put(Command.MFLUSH, new MapFlushHandler(this));
        commandHandlers.put(Command.MEVICT, new MapEvictHandler(this));
        commandHandlers.put(Command.MENTRYSET, new MapEntrySetHandler(this));
        commandHandlers.put(Command.KEYSET, new KeySetHandler(this));
        commandHandlers.put(Command.MGETENTRYVIEW, new MapGetEntryHandler(this));
        commandHandlers.put(Command.MFORCEUNLOCK, new MapForceUnlockHandler(this));
        commandHandlers.put(Command.MLISTEN, new MapListenHandler(this));
        commandHandlers.put(Command.MADDINTERCEPTOR, new MapAddIndexHandler(this));
        commandHandlers.put(Command.MREMOVEINTERCEPTOR, new MapExecuteOnKeyHandler(this));
        commandHandlers.put(Command.MEXECUTEONKEY, new MapExecuteOnKeyHandler(this));
        commandHandlers.put(Command.MEXECUTEONALLKEYS, new MapExecuteOnAllKeysHandler(this));
        commandHandlers.put(Command.MADDINDEX, new MapAddIndexHandler(this));
        return commandHandlers;
    }

    public void clientDisconnected(String clientUuid) {
        // TODO: @mm - release locks owned by this client.
    }

    public String addInterceptor(String mapName, MapInterceptor interceptor) {
        return getMapContainer(mapName).addInterceptor(interceptor);
    }

    public String removeInterceptor(String mapName, MapInterceptor interceptor) {
        return getMapContainer(mapName).removeInterceptor(interceptor);
    }

    // todo interceptors should get a wrapped object which includes the serialized version
    public Object interceptGet(String mapName, Object value) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        Object result = null;
        if (!interceptors.isEmpty()) {
            result = toObject(value);
            for (MapInterceptor interceptor : interceptors) {
                Object temp = interceptor.interceptGet(result);
                if (temp != null) {
                    result = temp;
                }
            }
        }
        return result == null ? value : result;
    }

    public void interceptAfterGet(String mapName, Object value) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        if (!interceptors.isEmpty()) {
            value = toObject(value);
            for (MapInterceptor interceptor : interceptors) {
                interceptor.afterGet(value);
            }
        }
    }

    public Object interceptPut(String mapName, Object oldValue, Object newValue) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        Object result = null;
        if (!interceptors.isEmpty()) {
            result = toObject(newValue);
            oldValue = toObject(oldValue);
            for (MapInterceptor interceptor : interceptors) {
                Object temp = interceptor.interceptPut(oldValue, result);
                if (temp != null) {
                    result = temp;
                }
            }
        }
        return result == null ? newValue : result;
    }

    public void interceptAfterPut(String mapName, Object newValue) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        if (!interceptors.isEmpty()) {
            newValue = toObject(newValue);
            for (MapInterceptor interceptor : interceptors) {
                interceptor.afterPut(newValue);
            }
        }
    }

    public Object interceptRemove(String mapName, Object value) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        Object result = null;
        if (!interceptors.isEmpty()) {
            result = toObject(value);
            for (MapInterceptor interceptor : interceptors) {
                Object temp = interceptor.interceptRemove(result);
                if (temp != null) {
                    result = temp;
                }
            }
        }
        return result == null ? value : result;
    }

    public void interceptAfterRemove(String mapName, Object value) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        if (!interceptors.isEmpty()) {
            for (MapInterceptor interceptor : interceptors) {
                value = toObject(value);
                interceptor.afterRemove(value);
            }
        }
    }

    public void publishWanReplicationUpdate(String mapName, EntryView entryView) {
        MapContainer mapContainer = getMapContainer(mapName);
        MapReplicationUpdate replicationEvent = new MapReplicationUpdate(mapName, mapContainer.getWanMergePolicy(), entryView);
        mapContainer.getWanReplicationListener().publishReplicationEvent(SERVICE_NAME, replicationEvent);
    }

    public void publishWanReplicationRemove(String mapName, Data key, long removeTime) {
        MapContainer mapContainer = getMapContainer(mapName);
        MapReplicationRemove replicationEvent = new MapReplicationRemove(mapName, key, removeTime);
        mapContainer.getWanReplicationListener().publishReplicationEvent(SERVICE_NAME, replicationEvent);
    }

    public void publishEvent(Address caller, String mapName, int eventType, Data dataKey, Data dataOldValue, Data dataValue) {
        Collection<EventRegistration> candidates = nodeEngine.getEventService().getRegistrations(SERVICE_NAME, mapName);
        Set<EventRegistration> registrationsWithValue = new HashSet<EventRegistration>();
        Set<EventRegistration> registrationsWithoutValue = new HashSet<EventRegistration>();
        if (candidates.isEmpty())
            return;
        Object key = null;
        Object value = null;
        Object oldValue = null;
        for (EventRegistration candidate : candidates) {
            EventFilter filter = candidate.getFilter();
            if (filter instanceof EventServiceImpl.EmptyFilter) {
                registrationsWithValue.add(candidate);
            } else if (filter instanceof QueryEventFilter) {
                Object testValue;
                if (eventType == EntryEvent.TYPE_REMOVED || eventType == EntryEvent.TYPE_EVICTED) {
                    oldValue = oldValue != null ? oldValue : toObject(dataOldValue);
                    testValue = oldValue;
                } else {
                    value = value != null ? value : toObject(dataValue);
                    testValue = value;
                }
                key = key != null ? key : toObject(key);
                QueryEventFilter queryEventFilter = (QueryEventFilter) filter;
                QueryEntry entry = new QueryEntry(getSerializationService(), dataKey, dataKey, testValue);
                if (queryEventFilter.eval(entry)) {
                    if (queryEventFilter.isIncludeValue()) {
                        registrationsWithValue.add(candidate);
                    } else {
                        registrationsWithoutValue.add(candidate);
                    }
                }
            } else if (filter.eval(dataKey)) {
                EntryEventFilter eventFilter = (EntryEventFilter) filter;
                if (eventFilter.isIncludeValue()) {
                    registrationsWithValue.add(candidate);
                } else {
                    registrationsWithoutValue.add(candidate);
                }
            }
        }
        if (registrationsWithValue.isEmpty() && registrationsWithoutValue.isEmpty())
            return;
        String source = nodeEngine.getThisAddress().toString();
        EventData event = new EventData(source, mapName, caller, dataKey, dataValue, dataOldValue, eventType);
        nodeEngine.getEventService().publishEvent(SERVICE_NAME, registrationsWithValue, event);
        nodeEngine.getEventService().publishEvent(SERVICE_NAME, registrationsWithoutValue, event.cloneWithoutValues());
    }

    public void addLocalEventListener(EntryListener entryListener, String mapName) {
        EventRegistration registration = nodeEngine.getEventService().registerLocalListener(SERVICE_NAME, mapName, entryListener);
        eventRegistrations.put(new ListenerKey(entryListener, null), registration.getId());
    }

    public void addEventListener(EntryListener entryListener, EventFilter eventFilter, String mapName) {
        EventRegistration registration = nodeEngine.getEventService().registerListener(SERVICE_NAME, mapName, eventFilter, entryListener);
        eventRegistrations.put(new ListenerKey(entryListener, ((EntryEventFilter) eventFilter).getKey()), registration.getId());
    }

    public void removeEventListener(EntryListener entryListener, String mapName, Object key) {
        String registrationId = eventRegistrations.get(new ListenerKey(entryListener, key));
        nodeEngine.getEventService().deregisterListener(SERVICE_NAME, mapName, registrationId);
    }

    public Object toObject(Object data) {
        if (data == null)
            return null;
        if (data instanceof Data) {
            return nodeEngine.toObject(data);
        } else {
            return data;
        }
    }

    public Data toData(Object object) {
        if (object == null)
            return null;
        if (object instanceof Data) {
            return (Data) object;
        } else {
            return nodeEngine.toData(object);
        }
    }

    public boolean compare(String mapName, Object value1, Object value2) {
        if (value1 == null && value2 == null) {
            return true;
        }
        if (value1 == null && value2 != null) {
            return false;
        }
        if (value1 != null && value2 == null) {
            return false;
        }

        MapContainer mapContainer = getMapContainer(mapName);
        if (mapContainer.getMapConfig().getInMemoryFormat().equals(MapConfig.InMemoryFormat.BINARY)) {
            return toData(value1).equals(toData(value2));
        } else if (mapContainer.getMapConfig().getInMemoryFormat().equals(MapConfig.InMemoryFormat.OBJECT)) {
            return toObject(value1).equals(toObject(value2));
        }
        return value1.equals(value2);
    }

    @SuppressWarnings("unchecked")
    public void dispatchEvent(EventData eventData, EntryListener listener) {
        Member member = nodeEngine.getClusterService().getMember(eventData.getCaller());
        EntryEvent event = new DataAwareEntryEvent(member, eventData.getEventType(), eventData.getSource(),
                eventData.getDataKey(), eventData.getDataNewValue(), eventData.getDataOldValue(), getSerializationService());
        switch (event.getEventType()) {
            case ADDED:
                listener.entryAdded(event);
                break;
            case EVICTED:
                listener.entryEvicted(event);
                break;
            case UPDATED:
                listener.entryUpdated(event);
                break;
            case REMOVED:
                listener.entryRemoved(event);
                break;
        }
        MapContainer mapContainer = getMapContainer(eventData.getMapName());
        if (mapContainer.getMapConfig().isStatisticsEnabled()) {
            getLocalMapStatsImpl(eventData.getMapName()).incrementReceivedEvents();
        }
    }

    public void scheduleIdleEviction(String mapName, Data key, long delay) {
        if (getMapContainer(mapName).getIdleEvictionScheduler() == null)
            getMapContainer(mapName).createIdleEvictionScheduler(nodeEngine);
        getMapContainer(mapName).getIdleEvictionScheduler().schedule(delay, key, null);

    }

    public void scheduleTtlEviction(String mapName, Data key, long delay) {
        getMapContainer(mapName).getTtlEvictionScheduler().schedule(delay, key, null);
    }

    public void scheduleMapStoreWrite(String mapName, Data key, Object value, long delay) {
        getMapContainer(mapName).getMapStoreWriteScheduler().schedule(delay, key, value);
    }

    public void scheduleMapStoreDelete(String mapName, Data key, long delay) {
        getMapContainer(mapName).getMapStoreDeleteScheduler().schedule(delay, key, null);
    }

    public SerializationService getSerializationService() {
        return nodeEngine.getSerializationService();
    }

    // todo map evict task is called every second. if load is very high, is it problem? if it is, you can count map-wide puts and fire map-evict in every thousand put
    // todo another "maybe" optimization run clear operation for all maps not just one map
    // todo what if eviction do not complete in 1 second
    private class MapEvictTask implements Runnable {
        public void run() {
            for (MapContainer mapContainer : mapContainers.values()) {
                MapConfig.EvictionPolicy evictionPolicy = mapContainer.getMapConfig().getEvictionPolicy();
                MaxSizeConfig maxSizeConfig = mapContainer.getMapConfig().getMaxSizeConfig();
                if (evictionPolicy != MapConfig.EvictionPolicy.NONE && maxSizeConfig.getSize() > 0) {
                    boolean check = checkLimits(mapContainer);
                    if (check) {
                        evictMap(mapContainer);
                    }
                }
            }
        }

        // todo call evict map listeners
        private void evictMap(MapContainer mapContainer) {
            MapConfig mapConfig = mapContainer.getMapConfig();
            MapConfig.EvictionPolicy evictionPolicy = mapConfig.getEvictionPolicy();
            Comparator comparator = null;
            if (evictionPolicy == MapConfig.EvictionPolicy.LRU) {
                comparator = new Comparator<AbstractRecord>() {
                    public int compare(AbstractRecord o1, AbstractRecord o2) {
                        return o1.getLastAccessTime().compareTo(o2.getLastAccessTime());
                    }

                    public boolean equals(Object obj) {
                        return this.equals(obj);
                    }
                };
            } else if (evictionPolicy == MapConfig.EvictionPolicy.LFU) {
                comparator = new Comparator<AbstractRecord>() {
                    public int compare(AbstractRecord o1, AbstractRecord o2) {
                        return o1.getHits().compareTo(o2.getHits());
                    }

                    public boolean equals(Object obj) {
                        return this.equals(obj);
                    }
                };
            }
            final int evictionPercentage = mapConfig.getEvictionPercentage();
            int memberCount = nodeEngine.getClusterService().getMembers().size();
            int targetSizePerPartition = -1;
            int maxPartitionSize = 0;
            final MaxSizeConfig.MaxSizePolicy maxSizePolicy = mapConfig.getMaxSizeConfig().getMaxSizePolicy();
            if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_NODE) {
                maxPartitionSize = (mapConfig.getMaxSizeConfig().getSize() * memberCount / nodeEngine.getPartitionService().getPartitionCount());
                targetSizePerPartition = Double.valueOf(maxPartitionSize * ((100 - evictionPercentage) / 100.0)).intValue();
            } else if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_PARTITION) {
                maxPartitionSize = mapConfig.getMaxSizeConfig().getSize();
                targetSizePerPartition = Double.valueOf(maxPartitionSize * ((100 - evictionPercentage) / 100.0)).intValue();
            }
            for (int i = 0; i < ExecutorConfig.DEFAULT_POOL_SIZE; i++) {
                nodeEngine.getExecutionService().execute("hz:map-evict", new EvictRunner(i, mapConfig, targetSizePerPartition, comparator, evictionPercentage));
            }
        }

        private class EvictRunner implements Runnable {
            final int mod;
            String mapName;
            int targetSizePerPartition;
            Comparator comparator;
            MaxSizeConfig.MaxSizePolicy maxSizePolicy;
            int evictionPercentage;

            private EvictRunner(int mod, MapConfig mapConfig, int targetSizePerPartition, Comparator comparator, int evictionPercentage) {
                this.mod = mod;
                mapName = mapConfig.getName();
                this.targetSizePerPartition = targetSizePerPartition;
                this.evictionPercentage = evictionPercentage;
                this.comparator = comparator;
                maxSizePolicy = mapConfig.getMaxSizeConfig().getMaxSizePolicy();
            }

            public void run() {
                for (int i = 0; i < nodeEngine.getPartitionService().getPartitionCount(); i++) {
                    if ((i % ExecutorConfig.DEFAULT_POOL_SIZE) != mod) {
                        continue;
                    }
                    Address owner = nodeEngine.getPartitionService().getPartitionOwner(i);
                    if (nodeEngine.getThisAddress().equals(owner)) {
                        PartitionContainer pc = partitionContainers[i];
                        final RecordStore recordStore = pc.getRecordStore(mapName);
                        List<Record> sortedRecords = new ArrayList<Record>(recordStore.getRecords().values());
                        Collections.sort(sortedRecords, comparator);
                        int evictSize = 0;
                        if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_NODE || maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_PARTITION) {
                            evictSize = Math.max((sortedRecords.size() - targetSizePerPartition), (sortedRecords.size() * evictionPercentage / 100 + 1));
                        } else {
                            evictSize = sortedRecords.size() * evictionPercentage / 100;
                        }

                        if (evictSize == 0)
                            continue;

                        Set<Data> keySet = new HashSet();
                        Iterator iterator = sortedRecords.iterator();
                        while (iterator.hasNext() && evictSize-- > 0) {
                            Record rec = (Record) iterator.next();
                            keySet.add(rec.getKey());
                        }
                        ClearOperation clearOperation = new ClearOperation(mapName, keySet);
                        clearOperation.setNodeEngine(nodeEngine);
                        clearOperation.setServiceName(SERVICE_NAME);
                        clearOperation.setResponseHandler(ResponseHandlerFactory.createEmptyResponseHandler());
                        clearOperation.setPartitionId(i);
                        nodeEngine.getOperationService().executeOperation(clearOperation);
                    }
                }
            }
        }

        private boolean checkLimits(MapContainer mapContainer) {
            MaxSizeConfig maxSizeConfig = mapContainer.getMapConfig().getMaxSizeConfig();
            MaxSizeConfig.MaxSizePolicy maxSizePolicy = maxSizeConfig.getMaxSizePolicy();
            String mapName = mapContainer.getName();
            // because not to exceed the max size much we start eviction early. so decrease the max size with ratio .95 below
            int maxSize = maxSizeConfig.getSize() * 95 / 100;
            if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_NODE || maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_PARTITION) {
                int totalSize = 0;
                for (int i = 0; i < nodeEngine.getPartitionService().getPartitionCount(); i++) {
                    Address owner = nodeEngine.getPartitionService().getPartitionOwner(i);
                    if (nodeEngine.getThisAddress().equals(owner)) {
                        int size = partitionContainers[i].getRecordStore(mapName).getRecords().size();  // TODO: can throw NPE during shutdown!
                        if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_PARTITION) {
                            if (size >= maxSize) {
                                return true;
                            }
                        } else {
                            totalSize += size;
                        }
                    }
                }
                return maxSizePolicy == MaxSizeConfig.MaxSizePolicy.PER_NODE && totalSize >= maxSize;
            }
            if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.USED_HEAP_SIZE || maxSizePolicy == MaxSizeConfig.MaxSizePolicy.USED_HEAP_PERCENTAGE) {
                long total = Runtime.getRuntime().totalMemory();
                long used = (total - Runtime.getRuntime().freeMemory());
                if (maxSizePolicy == MaxSizeConfig.MaxSizePolicy.USED_HEAP_SIZE) {
                    return maxSize > (used / 1024 / 1024);
                } else {
                    return maxSize > (used / total);
                }
            }
            return false;
        }
    }

    public QueryableEntrySet getQueryableEntrySet(String mapName) {
        List<Integer> memberPartitions = nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress());
        List<ConcurrentMap<Data, Record>> mlist = new ArrayList<ConcurrentMap<Data, Record>>();
        for (Integer partition : memberPartitions) {
            PartitionContainer container = getPartitionContainer(partition);
            RecordStore recordStore = container.getRecordStore(mapName);
            mlist.add(recordStore.getRecords());
        }
        return new QueryableEntrySet((SerializationServiceImpl) nodeEngine.getSerializationService(), mlist);
    }

    public void queryOnPartition(String mapName, Predicate predicate, int partitionId, QueryResult result) {
        PartitionContainer container = getPartitionContainer(partitionId);
        RecordStore recordStore = container.getRecordStore(mapName);
        ConcurrentMap<Data, Record> records = recordStore.getRecords();
        SerializationServiceImpl serializationService = (SerializationServiceImpl) nodeEngine.getSerializationService();
        for (Record record : records.values()) {
            Data key = record.getKey();
            QueryEntry queryEntry = new QueryEntry(serializationService, key, key, record.getValue());
            if (predicate.apply(queryEntry)) {
                result.add(new QueryResultEntryImpl(key, key, queryEntry.getValueData()));
            }
        }
    }

    public LocalMapStatsImpl createLocalMapStats(String mapName) {
        MapContainer mapContainer = getMapContainer(mapName);
        LocalMapStatsImpl localMapStats = getLocalMapStatsImpl(mapName);
        if (!mapContainer.getMapConfig().isStatisticsEnabled()) {
            return localMapStats;
        }

        long ownedEntryCount = 0;
        long backupEntryCount = 0;
        long dirtyCount = 0;
        long ownedEntryMemoryCost = 0;
        long backupEntryMemoryCost = 0;
        long hits = 0;
        long lockedEntryCount = 0;

        int backupCount = mapContainer.getTotalBackupCount();
        ClusterService clusterService = nodeEngine.getClusterService();
        final PartitionService partitionService = nodeEngine.getPartitionService();

        Address thisAddress = clusterService.getThisAddress();
        for (int partition = 0; partition < partitionService.getPartitionCount(); partition++) {
            PartitionInfo partitionInfo = partitionService.getPartitionInfo(partition);
            if (partitionInfo.getOwner().equals(thisAddress)) {
                PartitionContainer partitionContainer = getPartitionContainer(partition);
                RecordStore recordStore = partitionContainer.getRecordStore(mapName);
                ConcurrentMap<Data, Record> records = recordStore.getRecords();
                for (Record record : records.values()) {
                    RecordStatistics stats = record.getStatistics();
                    RecordState state = record.getState();
                    // there is map store and the record is dirty (waits to be stored)
                    if (mapContainer.getStore() != null && state.isDirty()) {
                        dirtyCount++;
                    }
                    ownedEntryCount++;
                    ownedEntryMemoryCost += record.getCost();
                    localMapStats.setLastAccessTime(stats.getLastAccessTime());
                    hits += stats.getHits();
                    if (recordStore.isLocked(record.getKey())) {
                        lockedEntryCount++;
                    }
                }
            } else {
                for (int replica = 1; replica <= backupCount; replica++) {
                    Address replicaAddress = partitionInfo.getReplicaAddress(replica);
                    int tryCount = 30;
                    // wait if the partition table is not updated yet
                    while (replicaAddress == null && clusterService.getSize() > backupCount && tryCount-- > 0) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            throw ExceptionUtil.rethrow(e);
                        }
                        replicaAddress = partitionInfo.getReplicaAddress(replica);
                    }

                    if (replicaAddress != null && replicaAddress.equals(thisAddress)) {
                        PartitionContainer partitionContainer = getPartitionContainer(partition);
                        RecordStore recordStore = partitionContainer.getRecordStore(mapName);
                        ConcurrentMap<Data, Record> records = recordStore.getRecords();
                        for (Record record : records.values()) {
                            backupEntryCount++;
                            backupEntryMemoryCost += record.getCost();
                        }
                    } else if (replicaAddress == null && clusterService.getSize() > backupCount) {
                        logger.log(Level.WARNING, "Partition: " + partition + ", replica: " + replica + " has no owner!");
                    }
                }
            }
        }

        localMapStats.setDirtyEntryCount(zeroOrPositive(dirtyCount));
        localMapStats.setLockedEntryCount(zeroOrPositive(lockedEntryCount));
        localMapStats.setHits(zeroOrPositive(hits));
        localMapStats.setOwnedEntryCount(zeroOrPositive(ownedEntryCount));
        localMapStats.setBackupEntryCount(zeroOrPositive(backupEntryCount));
        localMapStats.setOwnedEntryMemoryCost(zeroOrPositive(ownedEntryMemoryCost));
        localMapStats.setBackupEntryMemoryCost(zeroOrPositive(backupEntryMemoryCost));
        return localMapStats;
    }

    static long zeroOrPositive(long value) {
        return (value > 0) ? value : 0;
    }

}