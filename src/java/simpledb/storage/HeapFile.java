package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Debug;
import simpledb.common.Permissions;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.nio.Buffer;
import java.util.*;

/**
 * HeapFile is an implementation of a DbFile that stores a collection of tuples
 * in no particular order. Tuples are stored on pages, each of which is a fixed
 * size, and the file is simply a collection of those pages. HeapFile works
 * closely with HeapPage. The format of HeapPages is described in the HeapPage
 * constructor.
 * 
 * @see HeapPage#HeapPage
 * @author Sam Madden
 */
public class HeapFile implements DbFile {

    private final File file;

    private final TupleDesc td;

    // 已经有了numPages函数，根据文件获取页数,不需要专门设置一个属性页数，容易引起混乱
    // private int pageNum;

    /**
     * Constructs a heap file backed by the specified file.
     * 
     * @param f
     *            the file that stores the on-disk backing store for this heap
     *            file.
     */
    public HeapFile(File f, TupleDesc td) {
        // some code goes here
        this.file = f;
        this.td = td;
    }

    /**
     * Returns the File backing this HeapFile on disk.
     * 
     * @return the File backing this HeapFile on disk.
     */
    public File getFile() {
        // some code goes here
        return this.file;
    }

    /**
     * Returns an ID uniquely identifying this HeapFile. Implementation note:
     * you will need to generate this tableid somewhere to ensure that each
     * HeapFile has a "unique id," and that you always return the same value for
     * a particular HeapFile. We suggest hashing the absolute file name of the
     * file underlying the heapfile, i.e. f.getAbsoluteFile().hashCode().
     * 
     * @return an ID uniquely identifying this HeapFile.
     */
    public int getId() {
        // some code goes here
        return this.file.getAbsoluteFile().hashCode();
    }

    /**
     * Returns the TupleDesc of the table stored in this DbFile.
     * 
     * @return TupleDesc of this DbFile.
     */
    public TupleDesc getTupleDesc() {
        // some code goes here
        return this.td;
    }

    // see DbFile.java for javadocs
    public Page readPage(PageId pid) {
        // some code goes here
        if (pid.getPageNumber() >= numPages()) {
            throw new IllegalArgumentException("PageId的页号太大！");
        }
        // 要读取的页号
        int pgNo = pid.getPageNumber();
        try (
                RandomAccessFile r = new RandomAccessFile(this.file, "r");
        ) {
            byte[] bytes = new byte[BufferPool.getPageSize()];
            // 定位到读取的页的位置
            r.seek(pgNo * BufferPool.getPageSize());
            r.read(bytes, 0, BufferPool.getPageSize());
            HeapPageId pageId = new HeapPageId(getId(), pgNo);
            return new HeapPage(pageId, bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    // see DbFile.java for javadocs
    public void writePage(Page page) throws IOException {
        // some code goes here
        // not necessary for lab1
        int offset = page.getId().getPageNumber();
        try (
                RandomAccessFile w = new RandomAccessFile(this.file, "rw");
        ) {
            byte[] pageData = page.getPageData();
            w.seek(offset * BufferPool.getPageSize());
            w.write(pageData);
        }
    }

    /**
     * Returns the number of pages in this HeapFile.
     * 需要向上取整，即使不满一页也该分配一页
     */
    public int numPages() {
        // some code goes here
        return (int) Math.ceil(this.file.length() / (float) BufferPool.getPageSize());
    }

    // see DbFile.java for javadocs
    public List<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        if (!file.canWrite()) {
            throw new IOException("文件不能被读写");
        }
        if (!td.equals(t.getTupleDesc())) {
            throw new DbException("tupledesc is mismatch, the tuple can't be add!");
        }
        ArrayList<Page> pageList = new ArrayList<>();
        for (int i = 0; i < numPages(); i++) {
            HeapPageId pageId = new HeapPageId(getId(), i);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pageId, Permissions.READ_WRITE);
            if (page.getNumEmptySlots() != 0) {
                page.insertTuple(t);
                pageList.add(page);
                return pageList;
            }
        }

        // 没有包含空slot的页，需要创建新的页,（将空页刷到磁盘，相当于给文件增加一页的空间），然后从BufferPool获取页，将tuple插入页返回（此时为脏页）
        HeapPageId pageId = new HeapPageId(getId(), numPages());
        HeapPage heapPage = new HeapPage(pageId, HeapPage.createEmptyPageData());
        writePage(heapPage); // 只需将空页刷到磁盘，不需要将加入tuple的页（脏页）刷到磁盘

        // 脏页
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pageId, Permissions.READ_WRITE);
        page.insertTuple(t);
        pageList.add(page);
        return pageList;
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        PageId pageId = t.getRecordId().getPageId();
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pageId, Permissions.READ_WRITE);
        page.deleteTuple(t);
        ArrayList<Page> pageList = new ArrayList<>();
        pageList.add(page);
        return pageList;
    }

    /**
     * 迭代器，用来遍历HeapFile中的tuple
     */
    private class HeapFileIterator implements DbFileIterator {

        private TransactionId tid;
        private Iterator<Tuple> pageIterator;
        private int pgCursor;

        HeapFileIterator(TransactionId tid) {
            this.tid = tid;
        }

        @Override
        public void open() throws DbException, TransactionAbortedException {
            // 获取第0页的pageId
            PageId pageId = new HeapPageId(getId(), 0);
            // 从BufferPool中读页（可能是脏页？）
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pageId, Permissions.READ_ONLY);
            pageIterator = page.iterator();

        }

        @Override
        public boolean hasNext() throws DbException, TransactionAbortedException {
            if (pageIterator == null) {
                return false;
            }

            // 可能一整页都是空的需要循环，找到下一页
            do {
                if (pageIterator.hasNext()) {
                    return true;
                }
                // 当前页遍历完或页为空，切换到下一页
                pgCursor ++;
                if (pgCursor == numPages()) break;
                PageId pageId = new HeapPageId(getId(), pgCursor);
                HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pageId, Permissions.READ_ONLY);
                pageIterator = page.iterator();
            } while (this.pgCursor < numPages());

            return false;
        }

        @Override
        public Tuple next() throws DbException, TransactionAbortedException, NoSuchElementException {
            if (pageIterator == null || !pageIterator.hasNext()) {
                throw new NoSuchElementException();
            }

            return pageIterator.next();
        }

        @Override
        public void rewind() throws DbException, TransactionAbortedException {
            pgCursor = 0;
            open();
        }

        @Override
        public void close() {
            pgCursor = 0;
            pageIterator = null;
        }
    }

    // see DbFile.java for javadocs
    public DbFileIterator iterator(TransactionId tid) {
        // some code goes here
        return new HeapFileIterator(tid);
    }

}

