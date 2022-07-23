## Lab
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

### lab2
#### Filter and Join
实现谓词和operator \
谓词的核心是filter，需要借助field的compare方法。 \
operator（Join等）继承自Operator抽象类，Operator抽象类实现了OpIterator接口 \
operator的核心是实现迭代器，由于Operator已经提供了模板方法，故只需实现fetchNext等方法。 \
fetchNext需要借助谓词过滤不符合条件的元组，返回下一个符合条件的元组。\

需要注意的是：对于Join，如果是采用嵌套循环，需要找准每个child操作符使用next的时机。

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
由于，插入元组需要创建新的页，写到文件，因此**需要先把空的页写到文件，再通过BufferPool.getPage()获取页插入元组**，否则如果先插入元组，在将页写到文件，会导致返回的页不是脏页。
**修复BUG**：HeapFile.numPages()

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
