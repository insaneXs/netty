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

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */

/**
 * PoolChunk 指一整块的内存(通常为16M) 一个Chunk又划分为2048个Page(每个Page 8K)
 *  PoolChunk内部通过一个完全二叉树(其实是个堆?)memoryMap 管理Page的分配
 *  树一共12层(叶子结点正好2048个)
 *  memoryMap[i] 的值表示该节点的子节点中(包含自己)最大的空闲的节点在哪一层
 *  即：初始情况下memoryMap[i] == depthMap[i] 说明节点均还没有被分配
 *  如果memoryMap[i] >= depthMap[i] + 1 说明以该节点为根的子节点(或者说该节点的子节点)中已经有一部分被申请使用
 *  如果memoryMap[i] == maxOrder + 1 说明已经分配完
 *
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    //管理的负责管理的PoolArena
    final PoolArena<T> arena;
    //内存的类型 对于堆内存而言 为byte[]数组 对于direct内存而言 为byteBuffer(基于NIO提供的能力)
    final T memory;
    //是否需要缓存
    final boolean unpooled;
    //偏移量
    final int offset;

    //用来记录内存分配情况
    private final byte[] memoryMap;
    //记录每个idx对应的层数
    private final byte[] depthMap;
    //PoolChunk下的Subpage(用来管理小内存的分配)
    private final PoolSubpage<T>[] subpages;

    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    //用来判断申请的size是否超过一个Page
    private final int subpageOverflowMask;
    //每页的大小
    private final int pageSize;
    private final int pageShifts;
    //最大层数
    private final int maxOrder;
    //chunk的大小
    private final int chunkSize;
    private final int log2ChunkSize;
    //表示该Chunk最多允许能有几个Subpage
    private final int maxSubpageAllocs;

    /** Used to mark memory as unusable */
    //用来标记某个节点及其子节点已经无法分配 unusable = maxOrder + 1
    private final byte unusable;

    //记录剩余的字节
    private int freeBytes;

    //所在的PoolChunkList Chunk会根据自己的使用率，在PoolChunkList之间来回移动
    //这样做的有点试让使用率低的Chunk优先被使用 让整体的Chunk维持在一个使用率较高的水平
    PoolChunkList<T> parent;

    //PoolChunk之前形成双向链表
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * PoolChunk构造函数
     * @param arena 负责管理的PoolArena
     * @param memory Chunk使用的内存类型 heap or direct memory
     * @param pageSize Page的大小
     * @param maxOrder 树的深度
     * @param pageShifts
     * @param chunkSize Chunk的大小
     * @param offset
     */
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);

        //用来快速计算是否超过一页
        subpageOverflowMask = ~(pageSize - 1);
        //可用的大小
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        //默认支持的最大subpage数为 1 << maxOrder 即 所有的叶子节点均用作Subpage
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        //初始化memoryMap 和 depthMap
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        //创建subpage数组
        subpages = newSubpageArray(maxSubpageAllocs);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    //统计使用率 used / total
    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 分配内存
     * @param normCapacity
     * @return 返回对应的下标 如果是 > pageSize (8K)的 返回的是memoryMap中的idx
     *                      如果是 < pageSize （需要Subpage管理） 返回的是数前32位表示bitMapIdx 后32位表示memoryMapIdx
     */
    long allocate(int normCapacity) {
        //说明申请大于1页的内存
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity);
        } else { //说明申请小于一页的内存 为避免内存浪费 将一个Page拆分成多个SubPage进行管理
            return allocateSubpage(normCapacity);
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    //将父节点更新为已经分配
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    //将父节点更新为已经释放
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    /**
     * d 表示至少要分配的层数 例如16K 就需要在depth = 11层(层以1开始计数)有中可用的节点
     * 如何找这个点呢 就是从根节点一层层往下找:如果子节点还能够分配 则看左子节点是否可以 可以继续往下找 不可以 则看右子节点
     */
    private int allocateNode(int d) {
        //从根节点开始
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        //查memory[id]值 未分配时该值等于depth
        byte val = value(id);
        //大于 > d  说明可分配的节点所在的层数小于 < d 已经不可分配 返回 -1
        if (val > d) { // unusable
            return -1;
        }
        // 一层层往下找
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            //下一层的子节点
            id <<= 1;
            //看是否可以分配
            val = value(id);
            if (val > d) { //大于层数 说明不行 则使用另一个子节点
                id ^= 1;//异或这个操作太骚了 左右子节点来回切换
                val = value(id);
            }
        }

        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        //将该节点标记为不可分配
        setValue(id, unusable); // mark as unusable
        //更新父节点中memoryMap
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    //申请 大于1页内存的情况
    private long allocateRun(int normCapacity) {
        //计算属于哪层
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        //找出可以分配的idx
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        //更新可用的字节
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.

        //通过PoolArena找出内部的size是所需规格的Subpage
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        //相同规格的Subpage会在SubPagePools数组的相同坐标中 形成链表
        synchronized (head) {
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
            //找idx(对应哪个pageSize)
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;

            final int pageSize = this.pageSize;
            //更新可用字节数
            freeBytes -= pageSize;
            //应该是获取实际偏移量(叶子结点的偏移量 或者说在subpages数组中的偏移量)
            int subpageIdx = subpageIdx(id);
            //超找对应的PoolSubpage
            PoolSubpage<T> subpage = subpages[subpageIdx];
            //说明该Page页还没被分配为Subpage 创建SubPage
            if (subpage == null) {
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                //初始化Subpage 加入到SubPagePools中链表中
                subpage.init(head, normCapacity);
            }
            //通过Subpage管理分配小内存
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    /**
     * 释放操作
     * @param handle
     */
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        //说明释放的是subpage中的一小块内存
        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                //通过Subpage释放小部分内存  如果返回true 说明subpage还正在被使用 直接return 不需要释放关联的Page
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        //这里要做的是释放page
        //增加可用空间
        freeBytes += runLength(memoryMapIdx);
        //将memoryMap[idx]的值标记为可用
        setValue(memoryMapIdx, depth(memoryMapIdx));
        //更新父节点的memoryMap值
        updateParentsFree(memoryMapIdx);
    }

    //将内存分配给ByteBuf
    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        //获取对应memoryMap的index
        int memoryMapIdx = memoryMapIdx(handle);
        //获取位图的idx
        int bitmapIdx = bitmapIdx(handle);
        //说明是申请Page
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            //初始化PooledByteBuf
            buf.init(this, handle, runOffset(memoryMapIdx) + offset, reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {//小内存 通过SubPage给ByteBuf分配内存
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }
    }


    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }

    //由Subpage初始化ByteBuf
    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;
        //计算memoryMap idx
        int memoryMapIdx = memoryMapIdx(handle);
        //找出对应的Subpage
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;
        //分配
        buf.init(
            this, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
