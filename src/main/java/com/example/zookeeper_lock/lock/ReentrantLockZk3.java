package com.zdm.grapserver.util;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName ReentrantLockZk3
 * @Deacription TODO
 * @Author dinggang
 * @Date 2020/3/18
 * @Version 1.0
 * @Modefied what？
 **/
public class ReentrantLockZk3 {
    private String zkNodes = "127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183";
    private ZooKeeper zooKeeper = null;
    private String lockPath = null;
    private String parentLockPath = null;
    private int version;
    private CountDownLatch block;
    private String lastNodePath = null;

    /**
     * @Author dinggang
     * @Description //初始化锁
     * @Date 2020/3/17
     * @Param [parentLockPath, zkNodes]
     * parentLockPath参数表示指定的锁的父节点的路径名称,不带 /
     * zkNodes表示Zookeeper集群地址
     * @return
     * @throws
     **/
    public ReentrantLockZk3(String parentLockPath, String zkNodes) throws IOException, InterruptedException, KeeperException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        block = new CountDownLatch(1);
        this.parentLockPath = "/" + parentLockPath;
        this.zkNodes = zkNodes;
        //初始化Zookeeper对象
        zooKeeper = new ZooKeeper(zkNodes , 50000, new Watcher() {
            public void process(WatchedEvent event) {
                if (event.getState().equals(Event.KeeperState.SyncConnected)) {
                    countDownLatch.countDown();
                }
            }
        });
        //这个主要用来保证Zookeeper客户端连接对象的成功建立，然后才能进行后续操作
        countDownLatch.await();

        //创建父节点
        Stat stat = zooKeeper.exists(this.parentLockPath, false);
        if (stat == null) {
            ACL acl = new ACL(ZooDefs.Perms.ALL,ZooDefs.Ids.ANYONE_ID_UNSAFE);
            List<ACL> acls = new ArrayList<ACL>();
            acls.add(acl);
            //临时节点下无法创建子节点，所以只能采用永久节点
            this.parentLockPath = zooKeeper.create(this.parentLockPath, "parentLockPath".getBytes(), acls, CreateMode.PERSISTENT);
        }
    }

    /**
     * @Author dinggang
     * @Description //获取加锁
     * @Date 2020/3/17
     * @Param [lockPath, data]
     * lockPath子节点路径名称，不带/
     * data表示子节点上存储的数据
     * @return void
     * @throws
     **/
    public void lock(String lockPath, byte[] data) throws Exception {
        ACL acl = new ACL(ZooDefs.Perms.ALL,ZooDefs.Ids.ANYONE_ID_UNSAFE);
        List<ACL> acls = new ArrayList<ACL>();
        acls.add(acl);
        //创建临时有序节点
        String path = parentLockPath + "/" + lockPath;
        this.lockPath = zooKeeper.create(path, data, acls, CreateMode.EPHEMERAL_SEQUENTIAL);
        Stat stat = new Stat();
        //获取版本号，用于删除节点，释放锁实际上不删也可以，直接调用zooKeeper的close方法关闭客户端连接，效果相同，但是会慢一点
        zooKeeper.getData(this.lockPath,false, stat);
        version = stat.getVersion();
        //获取上一个节点的路径名,demo0000000809
        String str = this.lockPath.substring(path.length());
        Long number = Long.valueOf(str);
        number = number - 1;
        int count = String.valueOf(number).length();
        StringBuilder builder = new StringBuilder(path);
        for (int i = 1; i <= 10-count; i++) {
            builder.append(0);
        }
        builder.append(number);
        lastNodePath = builder.toString();
        //判断是否存在，同时启用事件监听器，监听该节点上的事件
        Stat stat1 = zooKeeper.exists(lastNodePath, new Watcher() {
            @Override
            public void process(WatchedEvent watchedEvent) {
                //节点被删除
                if (watchedEvent.getType().equals(Event.EventType.NodeDeleted)) {
                    System.out.println(lastNodePath+"节点被删除,监控的节点："+watchedEvent.getPath()+"，当前节点"+ReentrantLockZk3.this.lockPath);
                    block.countDown();
                }
                if (watchedEvent.getType().equals(Event.EventType.None)) {
                    System.out.println(lastNodePath+"节点不存在,"+"当前节点为头结点："+ReentrantLockZk3.this.lockPath);
                    block.countDown();
                }
            }
        });

        if (stat1 == null) {
            System.out.println(lastNodePath+"节点不存在,"+"，当前节点为头结点："+this.lockPath);
            block.countDown();
        }
        //主线程阻塞，直到异步方法中判断前一个节点已经不存在
        block.await(10000, TimeUnit.MILLISECONDS);
    }


    /**
     * @Author dinggang
     * @Description //释放锁
     * @Date 2020/3/17
     * @Param []
     * @return void
     * @throws
     **/
    public void unlock() throws IOException, KeeperException, InterruptedException {
        zooKeeper.delete(lockPath, version);
        zooKeeper.close();
    }

    public static void main(String[] args) {

//        ReentrantLockZk3 lockZk = null;
//        try {
//            Thread.sleep(10000);
//            //创建锁对象
//            lockZk = new ReentrantLockZk3("parent", "127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183");
//            //阻塞直到获取锁，
//            lockZk.lock("child", "data".getBytes());
//            //数量减一
//            secondKill.decrease();
//            //释放锁
//            lockZk.unlock();
//        } catch (IOException e) {
//            e.printStackTrace();
//        } catch (KeeperException e) {
//            e.printStackTrace();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }


        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                ReentrantLockZk3 lockZk = null;
                try {

                    //创建锁对象
                    lockZk = new ReentrantLockZk3("parent", "127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183");
                    //阻塞直到获取锁，
                    lockZk.lock("child", "data".getBytes());

                    Thread.sleep(10000);
                    //释放锁
                    lockZk.unlock();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (KeeperException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        };
        //启动101个线程进行测试，最后的输出结果应该为9899
        for (int i = 0; i <= 100; i++) {
            Thread t = new Thread(runnable);
            t.start();
        }
    }

}
