/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;


import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    final PoolArena<byte[]> heapArena;
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches;
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // Used for bitshifting when calculate the index of normal caches later
    private final int numShiftsNormalDirect;
    private final int numShiftsNormalHeap;
    private final int freeSweepAllocationThreshold;
    private final AtomicBoolean freed = new AtomicBoolean();

    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {

        if (maxCachedBufferCapacity < 0) {
            throw new IllegalArgumentException("maxCachedBufferCapacity: "
                    + maxCachedBufferCapacity + " (expected: >= 0)");
        }

        //设置相关变量
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;
        //如果需要管理Direct Memory
        if (directArena != null) {
            //创建Tiny类型的缓存池
            tinySubPageDirectCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            //创建Small类型的缓存池
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalDirect = log2(directArena.pageSize);
            //创建normal的缓存池
            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            //增加计数
            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }

        //如果需要管理heap memeory 方式同上
        if (heapArena != null) {
            // Create the caches for the heap allocations
            tinySubPageHeapCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalHeap = log2(heapArena.pageSize);
            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    /**
     * 创建Subpage的缓存池
     * @param cacheSize Cache的大小(内部队列的大小)
     * @param numCaches Cache的个数
     * @param sizeClass Cache的尺寸
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
            }
            return cache;
        } else {
            return null;
        }
    }

    /**
     * 创建Page的缓存池
     * @param cacheSize Cache的大小(内部队列的大小)
     * @param maxCachedBufferCapacity 最大能缓存的容量
     * @param area 负责管理的Arena 仅用来获取pageSize和ChunkSize
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            //确定池的大小 因为缓存都是只能是pageSize * 2的幂 的规格
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1);

            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[arraySize];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new NormalMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }

    //通过缓存分配内存
    /**
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForTiny(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForSmall(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForNormal(area, normCapacity), buf, reqCapacity);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    //分配内存
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        //从Cache分配内存
        boolean allocated = cache.allocate(buf, reqCapacity);
        //如果分配次数到达调整的阈值 压缩Cache
        if (++ allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    /**
     * 将不使用的内存添加到缓存池
     * @param area 负责管理的PoolArena
     * @param chunk 所处的Chunk
     * @param handle 索引
     * @param normCapacity 容量
     * @param sizeClass 规格
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, long handle, int normCapacity, SizeClass sizeClass) {
        MemoryRegionCache<?> cache = cache(area, normCapacity, sizeClass);
        if (cache == null) {
            return false;
        }
        return cache.add(chunk, handle);
    }

    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, normCapacity);
        case Small:
            return cacheForSmall(area, normCapacity);
        case Tiny:
            return cacheForTiny(area, normCapacity);
        default:
            throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free();
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    //释放缓存中的内存
    void free() {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(tinySubPageDirectCaches) +
                    free(smallSubPageDirectCaches) +
                    free(normalDirectCaches) +
                    free(tinySubPageHeapCaches) +
                    free(smallSubPageHeapCaches) +
                    free(normalHeapCaches);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return 0;
        }
        return cache.free();
    }

    void trim() {
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    //从Tiny缓存池中找适合的规格的Cache
    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        //确定这个大小在缓存中的第几个位置
        int idx = PoolArena.tinyIdx(normCapacity);
        if (area.isDirect()) {
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    //从Small缓存池中找适合的规格的Cache
    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        int idx = PoolArena.smallIdx(normCapacity);
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, idx);
        }
        return cache(smallSubPageHeapCaches, idx);
    }

    //从Normal缓存池中找适合规格的Cache
    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            return cache(normalDirectCaches, idx);
        }
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        return cache[idx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBufWithSubpage(buf, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBuf(buf, handle, reqCapacity);
        }
    }

    private abstract static class MemoryRegionCache<T> {
        //队列大小
        private final int size;
        //队列 元素可以理解为内存块
        private final Queue<Entry<T>> queue;
        //Region的大小 TINY SMALL NORMAL
        private final SizeClass sizeClass;
        //分配的次数
        private int allocations;

        //创建MemoryRegionCache
        MemoryRegionCache(int size, SizeClass sizeClass) {
            //确定size(2的幂次)
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            //创建multi-producer single-consumer的队列
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            //设置尺寸
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity);

        /**
         * Add to cache if not already full.
         */
        /**
         * 回收内存
         * @param chunk
         * @param handle
         * @return
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, long handle) {
            //创建Entry 代表要回收的内存
            Entry<T> entry = newEntry(chunk, handle);
            //尝试将内存入队
            boolean queued = queue.offer(entry);
            //如果无法入队 回收
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.recycle();
            }

            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         */
        //分配内存
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            //取出内存块
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }
            //将内存快分配给ByteBuf
            initBuf(entry.chunk, entry.handle, buf, reqCapacity);
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            ++ allocations;
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        public final int free() {
            return free(Integer.MAX_VALUE);
        }

        //释放这个RegionCache
        private int free(int max) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                //从队列中取出内存块
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    //释放内存块
                    freeEntry(entry);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         */
        public final void trim() {
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are
            if (free > 0) {
                free(free);
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;

            // recycle now so PoolChunk can be GC'ed.
            entry.recycle();

            chunk.arena.freeChunk(chunk, handle, sizeClass);
        }

        //可以理解为内存块
        static final class Entry<T> {
            final Handle<Entry<?>> recyclerHandle;
            PoolChunk<T> chunk;
            long handle = -1;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            //回收内存块
            void recycle() {
                //释放掉Chunk的引用
                chunk = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        /**
         * 创建一个元素表示内存块
         * @param chunk
         * @param handle
         * @return
         */
        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, long handle) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.handle = handle;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };
    }
}
