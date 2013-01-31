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

package com.hazelcast.spi.impl;

import com.hazelcast.config.ExecutorConfig;
import com.hazelcast.core.RuntimeInterruptedException;
import com.hazelcast.instance.Node;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.annotation.PrivateApi;
import com.hazelcast.util.ConcurrencyUtil;
import com.hazelcast.util.ExecutorThreadFactory;
import com.hazelcast.util.PoolExecutorThreadFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * @mdogan 12/14/12
 */
public final class ExecutionServiceImpl implements ExecutionService {

    private static final int DEFAULT_THREAD_SIZE = ExecutorConfig.DEFAULT_MAX_POOL_SIZE;

    private final ExecutorService cachedExecutorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ILogger logger;

    private final ConcurrentMap<String, ManagedExecutorService> executors = new ConcurrentHashMap<String, ManagedExecutorService>();

    public ExecutionServiceImpl(NodeEngineImpl nodeEngine) {
        Node node = nodeEngine.getNode();
        logger = node.getLogger(ExecutionService.class.getName());
        final ClassLoader classLoader = node.getConfig().getClassLoader();
        final ExecutorThreadFactory threadFactory = new PoolExecutorThreadFactory(node.threadGroup, node.hazelcastInstance,
                node.getThreadPoolNamePrefix("cached"), classLoader);
        cachedExecutorService = new ThreadPoolExecutor(
                1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), threadFactory, new RejectedExecutionHandler() {
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                logger.log(Level.FINEST, "Node is shutting down; discarding the task: " + r);
            }
        });
        scheduledExecutorService = Executors.newScheduledThreadPool(2,
                new PoolExecutorThreadFactory(node.threadGroup,
                        node.hazelcastInstance,
                        node.getThreadPoolNamePrefix("hz:scheduled"), classLoader));

        // default executors
        register("hz:system", 30);
        register("hz:client", 40);
        register("hz:scheduled", 10);
        register("hz:async-service", 20);

        final Collection<ExecutorConfig> executorConfigs = nodeEngine.getConfig().getExecutorConfigs();
        for (ExecutorConfig executorConfig : executorConfigs) {
            register(executorConfig.getName(), executorConfig.getMaxPoolSize());
        }
    }

    private void register(String name, int maxThreadSize) {
        executors.put(name, new ManagedExecutorService(name, maxThreadSize, 1));
    }

    private final ConcurrencyUtil.ConstructorFunction<String, ManagedExecutorService> constructor =
            new ConcurrencyUtil.ConstructorFunction<String, ManagedExecutorService>() {
                public ManagedExecutorService createNew(String name) {
                    // TODO: configure using ExecutorService config!
                    return new ManagedExecutorService(name, DEFAULT_THREAD_SIZE, 1);
                }
            };

    public ExecutorService getExecutor(String name) {
        return ConcurrencyUtil.getOrPutIfAbsent(executors, name, constructor);
    }

    public void execute(String name, Runnable command) {
        getExecutor(name).execute(command);
    }

    public Future<?> submit(String name, Runnable task) {
        return getExecutor(name).submit(task);
    }

    public <T> Future<T> submit(String name, Callable<T> task) {
        return getExecutor(name).submit(task);
    }

    public ScheduledFuture<?> schedule(final Runnable command, long delay, TimeUnit unit) {
        return scheduledExecutorService.schedule(new ScheduledRunner(command), delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, long initialDelay, long period, TimeUnit unit) {
        return scheduledExecutorService.scheduleAtFixedRate(new ScheduledRunner(command), initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, long initialDelay, long period, TimeUnit unit) {
        return scheduledExecutorService.scheduleWithFixedDelay(new ScheduledRunner(command), initialDelay, period, unit);
    }

    @PrivateApi
    void shutdown() {
        logger.log(Level.FINEST, "Stopping executors...");
        cachedExecutorService.shutdown();
        scheduledExecutorService.shutdownNow();
        try {
            cachedExecutorService.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.log(Level.FINEST, e.getMessage(), e);
        }
        for (ManagedExecutorService executorService : executors.values()) {
            executorService.destroy();
        }
        executors.clear();
    }

    @PrivateApi
    public void destroyExecutor(String name) {
        executors.remove(name);
    }

    private class ScheduledRunner implements Runnable {
        private final Runnable runnable;

        private ScheduledRunner(Runnable runnable) {
            this.runnable = runnable;
        }

        public void run() {
            execute("hz:scheduled", runnable);
        }
    }

    private class ManagedExecutorService implements ExecutorService {

        private final String name;

        private final BlockingQueue<Object> controlQ;

        private final int waitTime;

        private ManagedExecutorService(String name, int maxThreadSize, int waitTimeInSeconds) {
            this.name = name;
            this.waitTime = waitTimeInSeconds;
            this.controlQ = new ArrayBlockingQueue<Object>(maxThreadSize);
            for (int i = 0; i < maxThreadSize; i++) {
                controlQ.offer(new Object());
            }
        }

        public void execute(Runnable command) {
            final Object key = leaseKey();
            cachedExecutorService.execute(new ManagedRunnable(command, controlQ, key));
        }

        public <T> Future<T> submit(Callable<T> task) {
            final Object key = leaseKey();
            return cachedExecutorService.submit(new ManagedCallable<T>(task, controlQ, key));
        }

        public <T> Future<T> submit(Runnable task, T result) {
            final Object key = leaseKey();
            return cachedExecutorService.submit(new ManagedRunnable(task, controlQ, key), result);
        }

        public Future<?> submit(Runnable task) {
            final Object key = leaseKey();
            return cachedExecutorService.submit(new ManagedRunnable(task, controlQ, key));
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }

        private Object leaseKey() {
            final Object key;
            try {
                key = controlQ.poll(waitTime, TimeUnit.SECONDS);
                if (key == null) {
                    // TODO: improve logging...
                    logger.log(Level.WARNING, "Executor[" + name + "] is overloaded!");
                }
            } catch (InterruptedException e) {
                throw new RuntimeInterruptedException();
            }
            return key;
        }

        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        public boolean isShutdown() {
            return cachedExecutorService.isShutdown();
        }

        public boolean isTerminated() {
            return cachedExecutorService.isTerminated();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return cachedExecutorService.awaitTermination(timeout, unit);
        }

        void destroy() {
            controlQ.clear();
        }
    }

    private class ManagedRunnable implements Runnable {

        private final Runnable runnable;

        private final BlockingQueue<Object> q;

        private final Object key;

        private ManagedRunnable(Runnable runnable, BlockingQueue<Object> q, Object key) {
            this.runnable = runnable;
            this.q = q;
            this.key = key;
        }

        public void run() {
            try {
                runnable.run();
            } finally {
                releaseKey(key);
            }
        }

        private void releaseKey(final Object key) {
            if (key != null) {
                q.offer(key);
            }
        }
    }

    private class ManagedCallable<V> implements Callable<V> {

        private final Callable<V> callable;

        private final BlockingQueue<Object> q;

        private final Object key;

        private ManagedCallable(Callable<V> callable, BlockingQueue<Object> q, Object key) {
            this.callable = callable;
            this.q = q;
            this.key = key;
        }

        public V call() throws Exception {
            try {
                return callable.call();
            } finally {
                releaseKey(key);
            }
        }

        private void releaseKey(final Object key) {
            if (key != null) {
                q.offer(key);
            }
        }
    }

}