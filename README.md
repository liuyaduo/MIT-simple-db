## Lab

![](https://github.com/liuyaduo/MIT-simple-db/blob/dev/test.jpg)

### Lab1

#### Exercise 1： Fields and Tuples
schema: TupleDesc类 \
schema包含多个TDItem（属性类型（Type），属性名） \
Type：元组，包含INT_TYPE，STRING_TYPE \
tuple: Tuple类 \
tuple包含多个与schema对应的Field对象 \
Field: 目前只实现了IntField、StringField与Type对应

#### Exercise 2：Catalog
Catalog: 记录了数据库中的表 \
DbFile: 表对应的磁盘文件 \
name：表名 \
pkeyField：表主键 \
可以用一个内部类Table封装这三个表的属性,并通过哈希表获取对应的表

#### Exercise 3：BufferPool
难点：如何找到pageId对应的页 \
pid->tableId->dbFile->page
```
int pageTableId = pid.getTableId();
DbFile dbFile = Database.getCatalog().getDatabaseFile(pageTableId);
Page page = dbFile.readPage(pid);
```
各个类的包含关系：
DataBase->(BufferPool, Catalog) \
BufferPool->page（缓存页） \
Catalog->Table（内部类，包含表的信息：表的数据库文件，表名，主键）->(DbFile, name, pKeyField) \
DbFile(物理意义上的一张表)->Page（磁盘页） \
Page->Tuple (一个页可以存储多条记录)

#### Exercise 4：HeapFile access method
HeapPage：包含headers（bitmap）和tuples（tuple数组）\
PageId(tableId, pgNo):基于表Id和页号确定页Id, RecordId(PageId, tupleNo)：基于页Id和元组号确定记录Id\
**难点：**\
(1) 大端模式下的header字节数组（bitmap）中的bit和tuple的对应关系 \
    header（大端模式）：7 6 5 4 3 2 1 0 | 15 14 13 12 11 10 9 8 | ... \
(2) 如何利用位运算计算 \
(2.1) 某一个slot的状态（1：有效，0：无效（被删除、没有初始化（header要保证一个完整的字节，所以最后的bit可能没有对应的slot））） \
    isSlotUsed \
(2.2) 被删除的slot的个数 \
    getNumEmptySlots \
(3) 针对header实现迭代器 \
需要跳过被删除的slot

#### Exercise 5：HeapFile
难点：实现readPage和Iterator \
readPage需要从磁盘随机读取某一页
需要计算页的offset，使用RandomAccessFile对文件随机读写 \
Iterator需要返回DbFileIterator对象\
DbFileIterator：遍历文件中的页，并遍历页内的Tuple \
因此，需要获取某一页的迭代器，在页遍历结束后，切换到下一页。\
HeapFile迭代器重构: \
v1.在open中只加载第一页，在next中切换下一页。\
v2.由于可能存在一整页为空，故需要循环找到下一个不为空的页；另外将切换页移到了hasNext，next只管迭代

#### Exercise 6：Operators
实现SeqScan操作符：遍历整张表的Tuple \
需要注意的是在返回TupleDesc时，需要在FileName前加上别名

#### A simple query
遇到的bug：命令行使用的jdk版本和idea的版本不一样，导致使用idea编译后，命令行运行出错\
另外，'some_data_file.txt'在转换
（```java -jar dist/simpledb.jar convert some_data_file.txt 3```）
前是txt后缀，转换后会生成dat文件，开始理解错了，导致dat文件全是空字节。

### lab2：SimpleDB Operators
#### Filter and Join
实现谓词和operator \
谓词的核心是filter，需要借助field的compare方法。 \
operator（Join等）继承自Operator抽象类，Operator抽象类实现了OpIterator接口 \
operator的核心是实现迭代器，由于Operator已经提供了模板方法，故只需实现fetchNext等方法。 \
fetchNext需要借助谓词过滤不符合条件的元组，返回下一个符合条件的元组。\

需要注意的是：对于Join，如果是采用嵌套循环，需要找准child1和child2操作符切换的时机。\
v1 ：
```
while (true) {
   while (child2.hasNext()) {
      Tuple t2 = child2.next();
      if (p.filter(t1, t2)) {
          return merge(t1, t2);
      }
   }
   child2.rewind();
   if (!child1.hasNext()) {
       break;
   }
   t1 = child1.next();
}
return null;
```
2022.7.24 重构join
v2: 
```
while (child1.hasNext() || child2.hasNext()) {
    while (child2.hasNext()) {
        Tuple t2 = child2.next();
        if (p.filter(t1, t2)) {
            return merge(t1, t2);
        }
    }

    if (child1.hasNext()) {
        child2.rewind();
        t1 = child1.next();
    }
}

return null;
```

#### Aggregates
实现聚合操作，其中String类型的字段只能使用count聚合操作 /
实现的时候没有理清楚Aggregate和StringAggregator、IntegerAggregator的关系，导致花费了较长的时间/
StringAggregator和IntegerAggregator的核心是mergeTupleIntoGroup /
mergeTupleIntoGroup:把tuple根据分组字段gbField进行分组,并按照聚合操作符实现聚合操作。
由于要求空间复杂度为O(n),n为group个数，因此采用哈希表存储不同group的字段和它对应的聚合结果。
每执行一次mergeTupleIntoGroup都会更新一次group字段对应聚合结果。/
iterator函数根据哈希表的values创建TupleIterator对象并返回。/

而Aggregate则是利用实现的StringAggregator、IntegerAggregator，将child的所有tuple循环mergeTupleIntoGroup
具体使用StringAggregator还是IntegerAggregator，根据afield字段的类型来决定。

#### HeapFile Mutability
实现HeapPage、HeapFile、BufferPool的insertTuple和deleteTuple\
HeapPage实现在页内插入删除元组，删除为逻辑删除，将header中对应的bit置0.\
插入元组需要遍历找到页内空的slot。、\
HeapFile实现在文件内插入删除元组，比较关键的插入元组：\
需要对HeapFile中的页遍历找到存在空slot的页插入，如果没有的话，需要创建空页。\
需要注意的地方是，插入删除需要返回脏页，因此需要使用<tt>BufferPool.getPage()</tt>方法获取对应的页，不能直接在文件中写入。\
由于，插入元组需要创建新的页，写到文件，因此**需要先把空的页写到文件，再通过BufferPool.getPage()获取页插入元组**，否则如果先插入元组，在将页写到文件，会导致返回的页不是脏页。\
**修复BUG**：HeapFile.numPages()

#### Insertion and deletion
实现insert和deletion操作符。由于前面已经实现了insertTuple和deleteTuple，故整体实现较简单。\
**一处细节**导致system test始终通不过：\
由于insert和delete不管有没有插入元组，都要返回一个tuple，包含插入的个数。因此不能通过插入元组的个数判断fetchNext是否返回null(表示插入操作符的返回结果为空，迭代结束)\
因此，可以通过判断fetchNext是否被调用过，来判断是否返回null；\
**修复BUG**：HeapFile.HeapFileIterator.hasNext(): \
**if (pgCursor == numPages()) break;** -> **if (pgCursor == numPages()) break;** \
在跳出

#### Page eviction
使用LinkedHashMap容器，实现Lru淘汰策略 \
需要注意的是在flushAllPages中，迭代LinkedHashMap容器时，调用容器的get函数，会引起并发修改异常(ConcurrentModificationException)。\
因为get函数，会导致对应节点移动到链表的尾部。


### lab3：Query Optimization
#### Exercise1: IntHistogram
实现Int类型字段的直方图，计算选择率（selectivity）\
对范围[min, max]，分桶，然后基于（=, <, <=, >, >=, <>）计算概率。

#### Exercise2: TableStats
计算表中每个字段的直方图，需要先遍历计算每个int字段的最小值和最大值。\
然后基于min，max创建IntHistogram，最后将每个元组对应字段的值加入其中。、\
总共三次遍历。

#### Exercise3: Join Cost Estimation
estimateJoinCardinality：评估join得到的元组个数（Cardinality）
*  For equality joins, when one of the attributes is a primary key, the number of tuples produced by the join cannot
   be larger than the cardinality of the non-primary key attribute.
* For equality joins when there is no primary key, it's hard to say much about what the size of the output
  is -- it could be the size of the product of the cardinalities of the tables (if both tables have the
  same value for all tuples) -- or it could be 0.  It's fine to make up a simple heuristic (say,
  the size of the larger of the two tables).
*  For range scans, it is similarly hard to say anything accurate about sizes.
   The size of the output should be proportional to
   the sizes of the inputs.  It is fine to assume that a fixed fraction
   of the cross-product is emitted by range scans (say, 30%).  In general, the cost of a range
   join should be larger than the cost of a non-primary key equality join of two tables
   of the same size.

estimateJoinCost：计算join的时间花费（嵌套循环）
```
joincost(t1 join t2) = scancost(t1) + ntups(t1) x scancost(t2) //IO cost
                       + ntups(t1) x ntups(t2)  //CPU cost
```

#### Exercise4:  Join Ordering
优化**enumerateSubsets**：\
优化前：bigOrderJoinsTest花费**7s633ms**\
先计算长度为0的组合，然后根据长度为0的组合，添加一个元素，得到长度为1的组合，直至达到size。\
因此时间复杂度为O(n^size) (n为集合长度，size为要得到的组合的长度)\
然而对于size=n时，耗时最长，然后size=n，只有一种组合即集合本身，因此在计算size较大的组合时耗时很长。\
另一方面，空间复杂度也为O(n^size),需要花费大量的事件创建set。

优化后：bigOrderJoinsTest花费**1s833ms**\
时间复杂度为O(C_{n, size}), 即与返回的组合数成正比，当size较大时，如为n时，仅需常数时间。\
另一方面也解决了空间复杂度过高的问题。\
优化后实现用于返回组合结果的迭代器：
```
public static class CombinationIter<E> implements Iterator<Set<E>> {
    // 被组合的元素的个数
    private int len;
    // 组合的长度
    private int combLen;
    // 组合的位置
    private int[] orders;
    private boolean flag;

    private List<E> pool;
    private Set<E> combs;

    public CombinationIter(List<E> v, int len, int combLen) {
        if (len < combLen) {
            throw new IllegalArgumentException("len: " + len + " < " + "combLen: " + combLen);
        }
        this.len = len;
        this.combLen = combLen;
        orders = new int[combLen];
        for (int i = 0; i < combLen; i++) {
            orders[i] = i;
        }
        flag = true;
        pool = v;
    }

    @Override
    public boolean hasNext() {
        if (flag) return flag;
        int i;
        for (i = combLen - 1; i >= 0; i--) {
            if (orders[i] != i + len - combLen) {
                break;
            }
        }
        if (i < 0) {
            return false;
        }
        orders[i] ++;
        for (int j = i+1; j < combLen; j++) {
            orders[j] = orders[j-1] + 1;
        }
        return true;
    }

    @Override
    public Set<E> next() {
        flag = false;
        combs = new HashSet<>();
        for (int j = 0; j < combLen; j++) {
            combs.add(pool.get(orders[j]));
        }
        return combs;
    }
}
```

实现Selinger算法：实现对多个join操作的排序，使总join操作的代价最小（left-deep join）\
伪代码（本质上动态规划）：
```
1. j = set of join nodes
2. for (i in 1...|j|):
3.     for s in {all length i subsets of j}
4.       bestPlan = {}
5.       for s' in {all length i-1 subsets of s}
6.            subplan = optjoin(s')
7.            plan = best way to join (s-s') to subplan
8.            if (cost(plan) < cost(bestPlan))
9.               bestPlan = plan
10.      optjoin(s) = bestPlan
11. return optjoin(j)
```
其中6-9行，computeCostAndCardOfSubplan()函数已经实现\
由于长度为i的join集合需要借助i-1的join集合的cost等信息去计算最优的join方式（第7行），故需要记录i-1的信息，optjoin(s)为s的cost、order等缓存（PlanCache），

### Lab4: Transactions
主要是实现严格两阶段锁协议，保证事务的隔离性。\
**目前只实现了页级锁。**
#### Exercise1: Granting Locks
关键是实现LockManager，负责加锁和释放锁。\
1.加锁
```
* 当事务读某一页之前必须加共享锁
* 当事务写某一页之前必须加排他锁
* 共享锁可以被多个事务持有
* 排他锁只能被一个事务持有
* 当共享锁仅被一个事务持有时，则该事务可以升级锁为排他锁
```
需要在不能获取锁时，阻塞当前事务，因此决定采用Synchronized + wait/notifyAll，实现事务间的同步。\
利用两个哈希表存储在每个页上加共享锁/排他锁的事务，由于共享锁可能被多个事务共享，所以借助Set存储在某个页上加的事务。

2.释放锁 \
在某个事务释放锁之后，移除哈希表中对应的项，并调用notifyAll唤醒所有阻塞的事务。

3.判断事务是否对页加锁 \
从哈希表中查找即可

#### Exercise2:

insertTuple中当没有空闲slot的页时，会创建新的页，此时存在竞态条件，可以对insertTuple加锁来解决。

#### Exercise3:
实现**no-steal**：不能将未提交的事务修改的页刷到磁盘。（可以确保数据库不会处于一个中间状态，需要较大的内存来缓存未提交的修改，保证原子性）\
主要是修改**evictPage**方法，在淘汰页时，脏页不能被淘汰，需要切换到没有被修改的页淘汰。

#### Exercise4:
实现**transactionComplete()**方法：对应两阶段锁协议的收缩阶段，实现事务的提交/回滚，并释放事务持有的锁。\
提交：将事务修改的脏页刷到磁盘   --**force**（提交时将事务修改的所有脏页刷到磁盘，保证持久性。可能一次性写大量的页，影响性能）\
回滚：将脏页恢复成磁盘的中的数据，即修改前的状态。

现代的数据库一般都会采用steal/no force buffer管理策略来保证性能，并结合undo、redo保证原子性和持久性。

#### Exercise5:
实现死锁的检测和处理。\
1.死锁检测：
一种比较简单的策略时，超时回滚。但是这种策略时长不易设置，容易回滚没有死锁的事务。\
另一种策略是检测环，构建wait-for graph，图中的节点为事务，边t1->t2表示事务t1等待t2释放锁。当图中存在环时，即发生死锁。\

2.死锁处理
当检测到环时，回滚事务。此时涉及到回滚事务的选择，存在多种策略，比如锁住的对象个数，年龄，已经回滚的次数（可能存在饿死）。

**修复BUG：**transactionComplete(TransactionId tid, boolean commit)函数；**修复之后此时可以通过BTreeTest**。

```
如果事务是在insertTuple/deleteTuple如果在执行过程中就abort了，那么BufferPool中被事务获取的page还没有标记dirty，然而此时页可能已经发生了改变。所以不能通过页isDirty来判断是否回滚。
需要通过PageLockManager来查看哪些page被加了exclusive锁，并把这些page从disk读回。
```



除了上述的死锁检测和处理之外，还可以进行死锁预防，通过**wait-die**或**wound-die**。\

目前仅采取死锁检测和处理，死锁检测采用检测环的方法。\
实现了死锁检测器类，通过邻接表实现了图，并利用哈希表记录事务节点的入度。加入了加入边，移除边和检测环等方法。\
需要在加锁阻塞时，在图中加入边，在获取到锁后移除边。\
目前的实现为了简化，每次加锁失败后判断是否存在死锁，存在检测死锁频率高的问题。正常情况下应该周期性的检测死锁是否存在。\
死锁处理：在检测到死锁时，需要移除等待的边，并抛出`TransactionAbortedException`异常，事务的调用者捕捉到异常后回滚。 \
目前在回滚时只回滚检测到死锁的事务，即环中最后加入的边对应阻塞的事务。\

**后续可以尝试：**\
1.将死锁检测改为周期性检测；在事务回滚时尝试多种事务选择策略。
2.尝试死锁预防策略。
3.尝试引入更细粒度的锁：行级锁。

### Lab 5: B+ Tree Index

lab实现了一个B+Tree聚簇索引，用于快速查找和range scan。SimpleDB提供了B+树的框架，以及一些必要的函数，我们需要自己实现树搜索，分裂page，在page间重新分配tuples，合并page的方法。

BTreeFile包含四种不同的page：

首先是`BTreeInternalPage`和`BTreeLeafPage`，`BTreePage`接口包含了对这两种page的抽象；`BTreeHeaderPage`用于追踪哪些page正在被使用；`BTreeRootPtrPage`存在于每个BTreeFile的头部，指向RootPage（是一种`BTreeInternalPage`）和第一个HeaderPage。

如果想要允许多线程同时访问B+Tree，要防止以下两种问题：

- 多个thread同时修改一个node的内容；
- 一个thread正在traversing tree，其他线程在merge/split node。

simpledb在处理b+树的并发问题时采用如下的加锁策略：

- 对于scan，从root page到leaf page，加读锁；
- 对于insert，从root page开始的所有internal page都加读锁，Leaf page加写锁，如果涉及到split，就对sibling和parent加写锁，并且继续递归向上加写锁；
- 对于delete，直接对leaf page加写锁，如果涉及到steal/merge，再对sibling和parent加写锁，并且继续递归向上加写锁处理。

为了实现更高的并发度，可以参考B-link树。

#### Exercise 1： Search

实现B+树的查找操作，根据给定的Key，返回树中它在的那个叶子节点。

顺着根节点往下递归的找就是了，和二叉查找树/红黑树很相似，仅仅在于B+树有更多的叉，需要在一个节点当中遍历or二分查找刚好满足的Key及其Pointer。（由于simpledb在节点查找只支持迭代器，因此暂不支持二分查找）

顺着树根和树的内部节点一路递归下去，返回最终的（包含Key或者Key应该在的那个左）叶子节点。需要注意在getPage时，对于中间节点需要加读锁，叶节点加写锁。

#### Exercise 2： Insert-Splitting Pages

此处是实现插入操作的核心部分页分裂。如果是分裂叶子节点，需要将分裂之后的右侧子节点的第一个元素拷贝到父节点，并将父节点的左指针设置为左子节点，右指针设置为右子节点。如果是分裂中间节点需要需要将右侧子节点的第一个元素移除并拷贝到父节点。

在向父节点插入元素时可能引起父节点的分类，因此需要递归处理。

除此之外需要注意更新父指针，因为每个节点都存了对应父节点的指针，因此当创建新页之后需要更新父指针。



<p align="center"> <img width=500 src="splitting_leaf.png"><br> <img width=500
src="splitting_internal.png"><br> <i>Figure 2: Splitting pages</i> </p>





删除操作在删除之后节点中的元素数量小于最大数量的一半需处理两种情况：

1.重新分配两个页中的元素。

2.和另一个页小于最大数量一半的页合并

exercise3处理第一种，exercise4处理第2种。

#### Exercise 3： Delete-Redistributing pages

重新分配页种的元素分为1.分配叶子之间的元素，2.分配中间节点之间的元素

<p align="center"> <img width=500 src="redist_leaf.png"><br> <img width=500
src="redist_internal.png"><br> <i>Figure 3: Redistributing pages</i> </p>



#### Exercise 4： Delete-Merging pages



<p align="center"> <img width=500 src="merging_leaf.png"><br> <img width=500
src="merging_internal.png"><br> <i>Figure 4: Merging pages</i> </p>



#### 事务

Next-Key Lock

### Lab 6：Rollback and Recovery

目前的buffer 管理只实现了no-steal/force 策略，假设在提交的过程中数据库不会crash就可以保证事务的ACD特性。

```
Note that these three points mean that you do not need to implement log-based recovery in this lab, since you will never need to undo any work (you never evict dirty pages) and you will never need to redo any work (you force updates on commit and will not crash during commit processing).
```

此处实现的日志系统支持undo、redo，可用于处理更灵活的buffer 管理策略。

为了确保DBMS能够从failure中恢复，在事务的正常执行过程中需要做的事：

在事务执行中，写入WAL。仅当事务对应的log都被持久化之后，才能提交事务；当事务abort后，可以利用WAL log回滚。定期向持久化存储写入checkpoint，记录当前活跃的transactions和该事务第一次记录的位置。

WAL包含redo和undo信息：

- redo log必须是physical的，如对DB内某数据结构的修改，因为crash时，DBMS可能不满足“action consistent”，一些操作可能包含一系列非原子操作，例如插入一条数据，index中插入了，但是HeapFile中还没有。
- undo log必须时logical的，如delete/insert一条DB中的Tuple，因为当我们undo时，状态可能和写入该log时不一致了。

Simple DB目前实现的redo和undo全部是物理的。当一个事务更新过一个page后，对应的UPDATE日志record，会将记录的修改前的page内容作为before-image，将修改后的当前page内容作为after-image。之后，可以使用before-image在abort时回滚，或者在Recovery期间撤销loser transactions；使用after-image在Recovery期间redo winner transactions。

需要对原始代码做几处改变：

1.  在`BufferPool.flushPage()`中`writePage(p)`之前插入如下代码，保证WAL即刷盘之前先写日志。

```
// append an update record to the log, with 
// a before-image and after-image.
TransactionId dirtier = p.isDirty();
if (dirtier != null){
Database.getLogFile().logWrite(dirtier, p.getBeforeImage(), p);
Database.getLogFile().force();
}
```

2. 在`BufferPool.transactionComplete()`中`flushPage()`之后加入`p.setBeforeImage()`。用于在事务的更新提交后将该页的before-image更新。

```
// use current page contents as the before-image
// for the next transaction that modifies this page.
p.setBeforeImage();
```



#### Exercise1: Rollback

实现事务的回滚，undo事务对数据库的修改。

1.从事务的第一个记录开始，保存所有的UPDATE记录的before-image。

2.然后反向遍历所有的before-image，并写回磁盘，即可完成undo

#### Exercise2: Recovery

由于日志实现了CheckPoint，因此checkpoint之前的记录的修改已经写到磁盘。

此时需要对checkpoint之后的未提交的事务进行redo，对loser事务即checkpoint之前更新的未提交以及checkpoint之后开始但未提交的事务undo。

需要注意的是abort的事务既不undo也不redo。因为checkpoint之后的abort已经在crash之前回滚。

1. 从日志开始得到checkpoint记录的位置。
2. 使用哈希表保存每个事务对应的更新记录中的before-image用于undo，after-image用于redo
3. 在到达checkPoint之后记录提交的事务，并将事务即其要undo的before-image删除
4. 最终记录的undo中事务为loser事务，需要回滚，redo中的事务需要redo。



---

course-info
===========

GitHub Repo for http://dsg.csail.mit.edu/6.830/

We will be using git, a source code control tool, to distribute labs in 6.814/6.830. This will allow you to
incrementally download the code for the labs, and for us to push any hot fixes that might be necessary.

You will also be able to use git to commit and backup your progress on the labs as you go. Course git repositories will
be hosted as a repository in GitHub. GitHub is a website that hosts runs git servers for thousands of open source
projects. In our case, your code will be in a private repository that is visible only to you and course staff.`

This document describes what you need to do to get started with git, and also download and upload 6.830/6.814 labs via
GitHub.

**If you are not a registered student at MIT, you are welcome to follow along, but we ask you to please keep your solution PRIVATE and not make it
publicly available**

## Contents

- [Learning Git](#learning-git)
- [Setting up GitHub](#setting-up-github)
- [Installing Git](#installing-git)
- [Setting up Git](#setting-up-git)
- [Getting Newly Released Labs](#getting-newly-released-lab)
- [Word of Caution](#word-of-caution)
- [Help!](#help)

## Learning Git

There are numerous guides on using Git that are available. They range from being interactive to just text-based. Find
one that works and experiment; making mistakes and fixing them is a great way to learn. Here is a link to resources that
GitHub suggests:
[https://help.github.com/articles/what-are-other-good-resources-for-learning-git-and-github][resources].

If you have no experience with git, you may find the following web-based tutorial
helpful: [Try Git](https://try.github.io/levels/1/challenges/1).

## <a name="setting-up-github"></a> Setting Up GitHub

Now that you have a basic understanding of Git, it's time to get started with GitHub.

0. Install git. (See below for suggestions).

1. If you don't already have an account, sign up for one here: [https://github.com/join][join].

### Installing git <a name="installing-git"></a>

The instructions are tested on bash/linux environments. Installing git should be a simple `apt-get / yum / etc install`.

Instructions for installing git on Linux, OSX, or Windows can be found at
[GitBook:
Installing](http://git-scm.com/book/en/Getting-Started-Installing-Git).

If you are using Eclipse/IntelliJ, many versions come with git configured. The instructions will be slightly different than the
command line instructions listed but will work for any OS. Detailed instructions can be found
at [EGit User Guide](http://wiki.eclipse.org/EGit/User_Guide)
, [EGit Tutorial](http://eclipsesource.com/blogs/tutorials/egit-tutorial), or
[IntelliJ Help](https://www.jetbrains.com/help/idea/version-control-integration.html).

## Setting Up Git <a name="setting-up-git"></a>

You should have Git installed from the previous section.

1. The first thing we have to do is to clone the current lab repository by issuing the following commands on the command
   line:

   ```bash
    $ git clone https://github.com/MIT-DB-Class/simple-db-hw-2021.git
   ```

   Now, every time a new lab or patch is released, you can

   ```bash
    $ git pull
   ```
   to get the latest. 
   
   That's it. You can start working on the labs! That said, we strongly encourage you to use git for more than just
   downloading the labs. In the rest of the guide we will walk you through on how to use git for version-control
   during your own development. 

2. Notice that you are cloning from our repo, which means that it will be inappropriate for you to push your code to it.
   If you want to use git for version control, you will need to create your own repo to write your changes to. Do so 
   by clicking 'New' on the left in github, and make sure to choose **Private** when creating, so others cannot see your
   code! Now we are going to change the repo we just checked out to point to your personal repository.

3. By default the remote called `origin` is set to the location that you cloned the repository from. You should see the following:

   ```bash
    $ git remote -v
        origin https://github.com/MIT-DB-Class/simple-db-hw-2021.git (fetch)
        origin https://github.com/MIT-DB-Class/simple-db-hw-2021.git (push)
   ```

   We don't want that remote to be the origin. Instead, we want to change it to point to your repository. To do that, issue the following command:

   ```bash
    $ git remote rename origin upstream
   ```

   And now you should see the following:

   ```bash
    $ git remote -v
        upstream https://github.com/MIT-DB-Class/simple-db-hw-2021.git (fetch)
        upstream https://github.com/MIT-DB-Class/simple-db-hw-2021.git (push)
   ```

4. Lastly we need to give your repository a new `origin` since it is lacking one. Issue the following command, substituting your athena username:

   ```bash
    $ git remote add origin https://github.com/[your-repo]
   ```

   If you have an error that looks like the following:

   ```
   Could not rename config section 'remote.[old name]' to 'remote.[new name]'
   ```

   Or this error:

   ```
   fatal: remote origin already exists.
   ```

   This appears to happen to some depending on the version of Git they are using. To fix it, just issue the following command:

   ```bash
   $ git remote set-url origin https://github.com/[your-repo]
   ```

   This solution was found from [StackOverflow](http://stackoverflow.com/a/2432799) thanks to [Cassidy Williams](https://github.com/cassidoo).

   For reference, your final `git remote -v` should look like following when it's setup correctly:


   ```bash
    $ git remote -v
        upstream https://github.com/MIT-DB-Class/simple-db-hw-2021.git (fetch)
        upstream https://github.com/MIT-DB-Class/simple-db-hw-2021.git(push)
        origin https://github.com/[your-repo] (fetch)
        origin https://github.com/[your-repo] (push)
   ```

5. Let's test it out by doing a push of your master branch to GitHub by issuing the following:

   ```bash
    $ git push -u origin master
   ```

   You should see something like the following:

   ```
	Counting objects: 59, done.
	Delta compression using up to 4 threads.
	Compressing objects: 100% (53/53), done.
	Writing objects: 100% (59/59), 420.46 KiB | 0 bytes/s, done.
	Total 59 (delta 2), reused 59 (delta 2)
	remote: Resolving deltas: 100% (2/2), done.
	To git@github.com:MIT-DB-Class/homework-solns-2018-<athena username>.git
	 * [new branch]      master -> master
	Branch master set up to track remote branch master from origin.
   ```


6. That last command was a bit special and only needs to be run the first time to setup the remote tracking branches.
   Now we should be able to just run `git push` without the arguments. Try it and you should get the following:

   ```bash
    $ git push
      Everything up-to-date
   ```

If you don't know Git that well, this probably seemed very arcane. Just keep using Git and you'll understand more and
more. You aren't required to use commands like commit and push as you develop your labs, but will find them useful for
debugging. We'll provide explicit instructions on how to use these commands to actually upload your final lab solution.

## Getting Newly Released Labs <a name="getting-newly-released-lab"></a>

(You don't need to follow these instructions until Lab 1.)

Pulling in labs that are released or previous lab solutions should be easy as long as you set up your repository based
on the instructions in the last section.

1. All new lab and previous lab solutions will be posted to the [labs](https://github.com/MIT-DB-Class/simple-db-hw)
   repository in the class organization.

   Check it periodically as well as Piazza's announcements for updates on when the new labs are released.

2. Once a lab is released, pull in the changes from your simpledb directory:

   ```bash
    $ git pull upstream master
   ```

   **OR** if you wish to be more explicit, you can `fetch` first and then `merge`:

   ```bash
    $ git fetch upstream
    $ git merge upstream/master
   ```
   Now commit to your master branch:
   ```bash
	$ git push origin master
   ```

3. If you've followed the instructions in each lab, you should have no merge conflicts and everything should be peachy.

## <a name="word-of-caution"></a> Word of Caution

Git is a distributed version control system. This means everything operates offline until you run `git pull`
or `git push`. This is a great feature.

The bad thing is that you may forget to `git push` your changes. This is why we **strongly** suggest that you check
GitHub to be sure that what you want us to see matches up with what you expect.

## <a name="help"></a> Help!

If at any point you need help with setting all this up, feel free to reach out to one of the TAs or the instructor.
Their contact information can be found on the [course homepage](http://db.csail.mit.edu/6.830/).

[join]: https://github.com/join

[resources]: https://help.github.com/articles/what-are-other-good-resources-for-learning-git-and-github

[ssh-key]: https://help.github.com/articles/generating-ssh-keys
