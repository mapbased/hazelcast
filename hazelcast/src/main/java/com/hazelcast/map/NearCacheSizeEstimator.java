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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Size estimator for near cache.
 */
class NearCacheSizeEstimator implements SizeEstimator<NearCache.CacheRecord> {

    private final AtomicLong size = new AtomicLong(0L);

    protected NearCacheSizeEstimator() {
        super();
    }

    @Override
    public long getCost(NearCache.CacheRecord record) {
        // immediate check nothing to do if record is null
        if (record == null) {
            return 0;
        }
        final long cost = record.getCost();
        // if  cost is zero, type of cached object is not Data.
        // then omit.
        if (cost == 0) {
            return 0;
        }
        final int numberOfIntegers = 4;
        long size = 0;
        // entry size in CHM
        size += numberOfIntegers * ((Integer.SIZE / Byte.SIZE));
        size += cost;
        return size;
    }

    @Override
    public long getSize() {
        return size.longValue();
    }

    public void add(long size) {
        this.size.addAndGet(size);
    }

    public void reset() {
        size.set(0L);
    }
}
