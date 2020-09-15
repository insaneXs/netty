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
 * PoolSubpage 是针对小于一页的内存管理,避免申请极小的内存也要分配一页 造成内存浪费
 * 原理就是先申请一个Page，然后Subpage针对这一页按规格分成Tiny和Small两大类
 * 其中Tiny有31种小规格 16, 32, 48... 496(每个小规模递增16)
 * 而Small共有4种小规格:512，1024，2048，4096(每次成倍递增) （为什么不在考虑512的增加？ 应该有希望能整除一个Page页(8K)的考虑）
 * 大于4096的 将直接申请Page 不在由PoolSubpage管理
 * @param <T>
 */
final class PoolSubpage<T> implements PoolSubpageMetric {
    //关联的Chunk
    final PoolChunk<T> chunk;
    //这个PoolSubPage对应Chunk中page的idx
    private final int memoryMapIdx;
    //对应的page在叶子节点中的偏移量
    private final int runOffset;
    //页的大小
    private final int pageSize;
    //位数组 用来记录内存的分配情况
    private final long[] bitmap;

    //为什么要形成链表:这是因为在SubpagePool中 需要靠链表将相同规格的PoolSubpage组织在一起(刚在数组的同一下标中)
    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    //元素大小
    int elemSize;
    //最大能分配的元素个数
    private int maxNumElems;
    //实际需要使用的 bitmap的长度
    private int bitmapLength;
    //下个可用的偏移量
    private int nextAvail;
    //有多少个可用的元素
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * PoolSubpage 构造函数
     * @param head
     * @param chunk 关联的Chunk
     * @param memoryMapIdx 关联的Page在memory中的idx
     * @param runOffset 关联的Page在叶子节点的偏移
     * @param pageSize 页的大小
     * @param elemSize 元素的大小
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        //如何理解位数组的长度 (pageSize >>> 4) >>> 6  = pageSize / 16 / 64 => 说明SubPage的最小单位可以是16 最多能拆出512个 需要多少个64位表示
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64

        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        //记录元素大小
        this.elemSize = elemSize;
        if (elemSize != 0) {
            //计算这个Page可以存放多少这个页
            maxNumElems = numAvail = pageSize / elemSize;
            //下一个可用的偏移量
            nextAvail = 0;

            //通过元素的大小 计算实际位数组的长度(因为元素可能大于16B)
            bitmapLength = maxNumElems >>> 6;
            //长度至少为一个
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }
            //初始化位数组
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        //插入在节点后面
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    //分配内存
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        //从下一可用偏移开始
        final int bitmapIdx = getNextAvail();

        //计算在long数组的第一个元素
        int q = bitmapIdx >>> 6;
        //long中的哪各位
        int r = bitmapIdx & 63;

        assert (bitmap[q] >>> r & 1) == 0;
        //将该位设置为1
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) {
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    /**
     * 释放
     * @param head
     * @param bitmapIdx
     * @return 返回true 说明此Subpage还在被使用中
     *         返回false 说明此Subpage已经完全被释放了
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        //确定在第几个long元素上
        int q = bitmapIdx >>> 6;
        //确定在long元素的第几个位上
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        //修改该位的标志
        bitmap[q] ^= 1L << r;
        //更新下一可用的索引
        setNextAvail(bitmapIdx);

        //这个条件分成两步去看
        //STEP1: 如果numAvail == 0 说明之前Subpage是满的 但现在释放后 已经有部分空了 将其添加到SubPagePool中
        //numAvail ++ 更新可用数量
        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) { //未完全释放
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) { //如果是链表中的唯一节点的 先不移除
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            //标记销毁这个Subpage
            doNotDestroy = false;
            //从链表中移除
            removeFromPool();
            return false;
        }
    }

    //添加至链表中 作为头节点的后一个节点
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    //从链表中移除
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    //找下一个可用的位
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            //先确定在long数组的第几个元素
            if (~bits != 0) {
                //在确定long中的第几位
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
