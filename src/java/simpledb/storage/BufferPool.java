package simpledb.storage;

import simpledb.common.*;
import simpledb.transaction.Transaction;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;
import sun.misc.Lock;

import javax.swing.*;
import javax.xml.crypto.Data;
import java.io.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    // BufferPool已缓存页的数量
    private int pagesNum;

    // 缓存
    private Map<PageId, Page> cache;

    // 页数
    private int numPages;

    // 管理事务持有的锁
    private PageLockManager lockManager;

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        this.pagesNum = 0;
        this.numPages = numPages;

        cache = new LinkedHashMap<>(numPages, 0.75f, true);

        lockManager = new PageLockManager();
    }
    
    public static int getPageSize() {
      return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

//    class LockManager {
//
//        private Map<TransactionId, Set<PageId>> transactionMap;
//
//        private Map<PageId, ReadWriteLock> lockMap;
//
//        private Map<PageId, Permissions> permMap;
//
//        LockManager() {
//            transactionMap = new HashMap<>();
//            lockMap = new HashMap<>();
//            permMap = new HashMap<>();
//        }
//
//        /**
//         * 事务tid对pid对应的页进行加锁
//         * @param tid
//         * @param pid
//         * @param perm
//         */
//        public void addLock(TransactionId tid, PageId pid, Permissions perm) {
//            // 如果是同一个事务，则移除锁，之后重新创建锁
//            if (holdsLock(tid, pid)) {
//                lockMap.remove(pid);
//            }
//            ReadWriteLock lock = lockMap.getOrDefault(pid, new ReentrantReadWriteLock());
//            System.out.println(Thread.currentThread() + ": " + lock);
//
//            if (perm == Permissions.READ_ONLY) {
//                lock.readLock().lock();
//            } else {
//                lock.writeLock().lock();
//            }
//
//            if (!transactionMap.containsKey(tid)) {
//                transactionMap.put(tid, new HashSet<>());
//            }
//            transactionMap.get(tid).add(pid);
//            lockMap.put(pid, lock);
//            permMap.put(pid, perm);
//        }
//
//        /**
//         *
//         * @param tid
//         * @return
//         */
//        public boolean holdsLock(TransactionId tid, PageId pid) {
//            return transactionMap.getOrDefault(tid, new HashSet<>()).contains(pid);
//        }
//
//        /**
//         * 对事务tid在pid上的锁解锁
//         * @param tid
//         * @return
//         */
//        public void removeLock(TransactionId tid, PageId pid) {
//            if (!holdsLock(tid, pid)) {
//                throw new IllegalArgumentException("事务" + tid + "没有没有对" + pid + "加锁！");
//            }
//            Permissions perm = permMap.get(pid);
//            permMap.remove(pid);
//            transactionMap.get(tid).remove(pid);
//            ReadWriteLock lock = lockMap.get(pid);
//            if (perm == Permissions.READ_ONLY) {
//                lock.readLock().unlock();
//            } else {
//                lock.writeLock().unlock();
//            }
//        }
//
//    }

    public class DeadLockDetector {

        // 等待图（wait-for graph）：节点为事务，t1-t2的边表示t1事务等待t2事务
        private Map<TransactionId, Set<TransactionId>> graph = new HashMap<>();

        // 入度
        Map<TransactionId, Integer> inDegree = new HashMap<>();

        /**
         * 在等待图中加入边t1 -> t2
         * @param t1
         * @param t2
         */
        public void addWaitEdge(TransactionId t1, TransactionId t2) {
            if (!graph.containsKey(t1)) {
                graph.put(t1, new HashSet<>());
            }
            graph.get(t1).add(t2);
            //inDegree.put(t1, inDegree.getOrDefault(t1, 0));
            inDegree.put(t2, inDegree.getOrDefault(t2, 0) + 1);
        }

        /**
         * 移除等待图中与t1等待的边
         * @param t1
         */
        public void removeEdge(TransactionId t1) {
            if (graph.containsKey(t1)) {
                for (TransactionId transactionId : graph.get(t1)) {
                    if (inDegree.containsKey(transactionId)) {
                        inDegree.put(transactionId, inDegree.get(transactionId) - 1);
                        if (inDegree.get(transactionId) == 0) {
                            inDegree.remove(transactionId);
                        }
                    }
                }
            }

            graph.remove(t1);
        }

        /**
         * 检测是否存在死锁
         * ：采用拓扑排序检测环
         * @return 是否存在死锁
         */
        public boolean detect() {

            Map<TransactionId, Integer> tempInDegree = new HashMap<>(inDegree);
            // 记录入度为0的节点
            Deque<TransactionId> queue = new LinkedList<>();
            for (Map.Entry<TransactionId, Set<TransactionId>> entry : graph.entrySet()) {
                TransactionId inTid = entry.getKey();
                if (!tempInDegree.containsKey(inTid) || tempInDegree.get(inTid) == 0) {
                    queue.offer(inTid);
                }
            }

            while (queue.size() > 0) {
                TransactionId t = queue.poll();
                if (!graph.containsKey(t)) continue;
                for (TransactionId adjTid: graph.get(t)) {
                    int ind = tempInDegree.get(adjTid) - 1;
                    tempInDegree.put(adjTid, ind);
                    if (ind == 0) {
                        queue.offer(adjTid);
                    }
                }
            }

            for (Integer ind : tempInDegree.values()) {
                if (ind > 0) {
                    return true;
                }
            }
            return false;
        }

    }

    public class PageLockManager {

        // 在页上加读锁的事务
        private final Map<PageId, Set<TransactionId>> sLockMap = new HashMap<>();

        // 在页上加写锁的事务
        private final Map<PageId, TransactionId> xLockMap = new HashMap<>();

        private final Map<TransactionId, Set<PageId>> transactionHoldPage = new HashMap<>();

        private final DeadLockDetector deadLockDetector = new DeadLockDetector();

        public PageLockManager() {

        }

        /**
         * 根据perm的类型实现事务tid对页pid加共享锁/排他锁
         * @param tid
         * @param pid
         * @param perm
         */
        public void lock(TransactionId tid, PageId pid, Permissions perm)
                throws TransactionAbortedException {

            synchronized (this) {
                while (!tryLock(tid, pid, perm)) {

                    // 死锁检测
                    if (deadLockDetector.detect()) {

                        // 死锁，移除事务等待的边
                        deadLockDetector.removeEdge(tid);
                        throw new TransactionAbortedException();
                    }

                    // 无法获取锁，阻塞
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                // 加入事务锁的页
                if (!transactionHoldPage.containsKey(tid)) {
                    transactionHoldPage.put(tid, new HashSet<>());
                }
                transactionHoldPage.get(tid).add(pid);
            }

        }

        /**
         * 判断是否可以获取锁
         * 1. 当事务读某一页之前必须加共享锁
         * 2. 当事务写某一页之前必须加排他锁
         * 3. 共享锁可以被多个事务持有
         * 4. 排他锁只能被一个事务持有
         * 5. 当共享锁仅被一个事务持有时，则该事务可以升级锁为排他锁
         * @param tid
         * @param pid
         * @param perm
         * @return
         */
        public boolean tryLock(TransactionId tid, PageId pid, Permissions perm) {
            // 页pid上没有加锁(1,2)
            if (!sLockMap.containsKey(pid) && !xLockMap.containsKey(pid)) {
                if (perm == Permissions.READ_ONLY) {
                    sLockMap.put(pid, new HashSet<>());
                    sLockMap.get(pid).add(tid);
                } else {
                    xLockMap.put(pid, tid);
                }
                // 获取锁成功，移除tid等待的边
                this.deadLockDetector.removeEdge(tid);
                return true;
            }

            if (xLockMap.containsKey(pid)) { // 页pid上加了排他锁

                if (tid.equals(xLockMap.get(pid))) {
                    deadLockDetector.removeEdge(tid);
                    return true;
                }
                // 在wait-for graph中加入边
                deadLockDetector.addWaitEdge(tid, xLockMap.get(pid));
                // 其他事务已经加了排他锁, 被阻塞(4)
                return false;
            } else { // 页pid上加了共享锁

                if (perm == Permissions.READ_ONLY) {
                    // 事务企图加共享锁，不会被阻塞(3)
                    sLockMap.get(pid).add(tid);
                    deadLockDetector.removeEdge(tid);
                    return true;
                } else if (sLockMap.get(pid).size() == 1 && sLockMap.get(pid).contains(tid)){
                    // tid企图加写锁，且目前持有读锁的事务只有tid，则锁升级(5)
                    sLockMap.remove(pid);
                    xLockMap.put(pid, tid);
                    deadLockDetector.removeEdge(tid);
                    return true;
                } else {
                    // 在wait-for graph中加入边
                    for (TransactionId sTid : sLockMap.get(pid)) {
                        if (tid.equals(sTid)) continue;
                        deadLockDetector.addWaitEdge(tid, sTid);
                    }

                    return false;
                }
            }

        }

        public void unlock(TransactionId tid, PageId pid) {
            synchronized (this) {
                if (xLockMap.containsKey(pid)) {
                    if (xLockMap.get(pid).equals(tid)) {
                        xLockMap.remove(pid);
                    }
                } else {
                    sLockMap.get(pid).remove(tid);
                    if (sLockMap.get(pid).size() == 0) {
                        sLockMap.remove(pid);
                    }
                }
                transactionHoldPage.get(tid).remove(pid);
                if (transactionHoldPage.get(tid).size() == 0) {
                    transactionHoldPage.remove(tid);
                }

                this.notifyAll();
            }
        }

        public synchronized boolean holdsLock(TransactionId tid, PageId pid) {
            return (xLockMap.containsKey(pid) && xLockMap.get(pid).equals(tid)) || (sLockMap.containsKey(pid) && sLockMap.get(pid).contains(tid));
        }

        public synchronized PageId[] holdsPage(TransactionId tid) {
            if (!transactionHoldPage.containsKey(tid)) return new PageId[0];
            return transactionHoldPage.get(tid).toArray(new PageId[0]);
        }

        public synchronized boolean holdsXPage(TransactionId tid, PageId pid) {
            return xLockMap.containsKey(pid) && xLockMap.get(pid).equals(tid);
        }

    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
            throws TransactionAbortedException, DbException {
        // some code goes here
        // 加页级锁
        lockManager.lock(tid, pid, perm);
        if (!cache.containsKey(pid)) {
            if (pagesNum == this.numPages) {
                try {
                    evictPage();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            // 读取pid对应的page
            int pageTableId = pid.getTableId();
            DbFile dbFile = Database.getCatalog().getDatabaseFile(pageTableId);
            Page page = dbFile.readPage(pid);

            // 将page加入缓存
            cache.put(pid, page);
            pagesNum++;
        }
        return cache.get(pid);


    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public  void unsafeReleasePage(TransactionId tid, PageId pid) {
        // some code goes here
        // not necessary for lab1|lab2
        lockManager.unlock(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) {
        // some code goes here
        // not necessary for lab1|lab2
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for lab1|lab2
        return lockManager.holdsLock(tid, p);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit) {
        // some code goes here
        // not necessary for lab1|lab2
        PageId[] pageIds = this.lockManager.holdsPage(tid);
        // 释放事务持有的锁,并提交或回滚对应的页
        for (PageId pid: pageIds) {
            Page p = cache.get(pid);
            // https://www.wmc1999.top/posts/mit-6.830-lab4-simpledb-transactions/
            // 如果事务是在insertTuple/deleteTuple执行完之后再abort，
            // 那么由于no steal，abort时该事务的dirty page一定在BufferPool中被标记为dirty了，
            // 通过遍历BufferPool中的page，并将dirty page读回是可行的；
            // 但如果在执行过程中就abort了，那么BufferPool中被事务获取的page还没有标记dirty，
            // 我们需要通过PageLockManager来查看哪些page被加了exclusive锁，并把这些page从disk读回。
            if (p != null && this.lockManager.holdsXPage(tid, pid)) {
                if (commit) {
                    // 提交：将页刷到磁盘
                    try {
                        flushPage(pid);
                        // use current page contents as the before-image
                        // for the next transaction that modifies this page.
                        p.setBeforeImage();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    // 回滚：将页恢复到磁盘上页的状态
                    cache.put(pid, Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid));
                }
                p.markDirty(false, null); // 清除页dirty的标记
            }
            this.lockManager.unlock(tid, pid);
        }

    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        DbFile dbFile = (DbFile) Database.getCatalog().getDatabaseFile(tableId);
        List<Page> dirtyPages = dbFile.insertTuple(tid, t);
        for (Page dirtyPage : dirtyPages) {
            dirtyPage.markDirty(true, tid);
            cache.put(dirtyPage.getId(), dirtyPage);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public  void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        PageId pageId = t.getRecordId().getPageId();
        Page page = getPage(tid, pageId, Permissions.READ_WRITE);
        DbFile dbFile = Database.getCatalog().getDatabaseFile(page.getId().getTableId());
        List<Page> dirtyPages = dbFile.deleteTuple(tid, t);
        for (Page dirtyPage : dirtyPages) {
            dirtyPage.markDirty(true, tid);
            cache.put(dirtyPage.getId(), dirtyPage);
        }
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for lab1

        // LinkedHashMap迭代器循坏内，调用cache.get等方法会改变容器结构，引起并发修改异常
        for (Map.Entry<PageId, Page> e : cache.entrySet()) {
            Page page = e.getValue();
            //Page page = e.getValue();
            //Database.getCatalog().getDatabaseFile(pid.getTableId()).writePage(page);
            //page.markDirty(false, null);
            flushPage(page);
        }
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
        // not necessary for lab1
        cache.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for lab1

        if (cache.containsKey(pid)) {
            // append an update record to the log, with
            // a before-image and after-image.
            Page p = cache.get(pid);
            TransactionId dirtier = p.isDirty();
            if (dirtier != null){
                Database.getLogFile().logWrite(dirtier, p.getBeforeImage(), p);
                Database.getLogFile().force();
            }
            // 将脏页刷到磁盘
            Database.getCatalog().getDatabaseFile(pid.getTableId()).writePage(p);
            p.markDirty(false, null);
        }
    }

    private synchronized void flushPage(Page page) throws IOException {
        // some code goes here
        // not necessary for lab1

        // append an update record to the log, with
        // a before-image and after-image.
        TransactionId dirtier = page.isDirty();
        if (dirtier != null){
            Database.getLogFile().logWrite(dirtier, page.getBeforeImage(), page);
            Database.getLogFile().force();
        }
        // 将脏页刷到磁盘
        PageId pid = page.getId();
        Database.getCatalog().getDatabaseFile(pid.getTableId()).writePage(page);
        page.markDirty(false, null);
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized  void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        PageId[] pageIds = this.lockManager.holdsPage(tid);
        TransactionId dirtyTid;
        for (PageId pid: pageIds) {
            // 提交：将页刷到磁盘
            if (cache.containsKey(pid) && ((dirtyTid = cache.get(pid).isDirty()) != null) && dirtyTid.equals(tid)) {
                try {
                    flushPage(pid);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized  void evictPage() throws DbException, IOException {
        // some code goes here
        // not necessary for lab1
        Iterator<Map.Entry<PageId, Page>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PageId, Page> oldestPage = iterator.next();
            Page page = oldestPage.getValue();

            // 脏页不能被淘汰
            if (page.isDirty() != null) {
                continue;
            }

            //PageId pageId = oldestPage.getKey();
            // 只淘汰干净的页，不需要刷盘（no steal）
            //flushPage(pageId);
            //System.out.println("淘汰页：" + oldestPage.getKey());
            iterator.remove();
            pagesNum--;
            return;
        }
        throw new DbException("没有可以淘汰的页！");
    }

}
