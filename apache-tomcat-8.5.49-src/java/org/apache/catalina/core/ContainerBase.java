/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.core;

import org.apache.catalina.*;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.HostConfig;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.MultiThrowable;
import org.apache.tomcat.util.res.StringManager;

import javax.management.ObjectName;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Abstract implementation of the <b>Container</b> interface, providing common
 * functionality required by nearly every implementation.  Classes extending
 * this base class must may implement a replacement for <code>invoke()</code>.
 * Container接口的实现类，提供几乎每个实现此接口的类都需要的公共功能。如果类要继承
 *  这个基础类必须去实现并替换掉<>invoke()</>方法。
 * <p>
 * All subclasses of this abstract base class will include support for a
 * Pipeline object that defines the processing to be performed for each request
 * received by the <code>invoke()</code> method of this class, utilizing the
 * "Chain of Responsibility" design pattern.  A subclass should encapsulate its
 * own processing functionality as a <code>Valve</code>, and configure this
 * Valve into the pipeline by calling <code>setBasic()</code>.
 * 这个抽象类的所有子类，都会含有对Pipeline对象的支持的功能，Pipeline对象利用责任链
 * 的设计模式，定义了对这个类的<code>invoke()</code>方法接收到的每个request执行的的处理支持。
 * 子类应该将自己的处理功能封装为一个Valve，并通过调用setBasic()将这个Valve配置到管道中。
 * <p>
 * This implementation fires property change events, per the JavaBeans design
 * pattern, for changes in singleton properties.  In addition, it fires the
 * following <code>ContainerEvent</code> events to listeners who register
 * themselves with <code>addContainerListener()</code>:
 * 此实现类通过触发多个属性更改事件(events),依照JavaBean的设计模式，去用于单例属性的改变。
 * 除此之外，还向那些注册了自己addContainerListener的监听器发出ContainerEvent事件。
 * <table border=1>
 *   <caption>ContainerEvents fired by this implementation</caption> //ContainerEvents被触发在这个实现类上。
 *   <tr>
 *     <th>Type</th>
 *     <th>Data</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td><code>addChild</code></td> //增加子容器
 *     <td><code>Container</code></td> //容器
 *     <td>Child container added to this Container.</td> //添加到此容器的子容器
 *   </tr>
 *   <tr>
 *     <td><code>{@link #getPipeline() pipeline}.addValve</code></td> //增加阀门。
 *     <td><code>Valve</code></td> //阀门
 *     <td>Valve added to this Container.</td> //在这个容器上增加阀门
 *   </tr>
 *   <tr>
 *     <td><code>removeChild</code></td>
 *     <td><code>Container</code></td>
 *     <td>Child container removed from this Container.</td>
 *   </tr>
 *   <tr>
 *     <td><code>{@link #getPipeline() pipeline}.removeValve</code></td>
 *     <td><code>Valve</code></td>
 *     <td>Valve removed from this Container.</td>
 *   </tr>
 *   <tr>
 *     <td><code>start</code></td>
 *     <td><code>null</code></td>
 *     <td>Container was started.</td> //容器被启动了
 *   </tr>
 *   <tr>
 *     <td><code>stop</code></td>
 *     <td><code>null</code></td>
 *     <td>Container was stopped.</td> //容器被停止了。
 *   </tr>
 * </table>
 * Subclasses that fire additional events should document them in the
 * class comments of the implementation class.
 * 在实现类的注释中应该记录下引发其他事件的子类。6个标准类。
 *
 * Pipeline作为Value的传递的载体。也就是Filter和Filter的关系。Pipeline作为衔接其他容器
 * 之间的桥梁，将其所需功能抽象出来，形成ContainerBase。
 *
 * @author Craig R. McClanahan
 * @translator chenchen6(chenmudu@gmail.com/chenchen6@tuhu.cn)
 */
public abstract class ContainerBase extends LifecycleMBeanBase
        implements Container { //LifecycleMBeanBase and Container

    private static final Log log = LogFactory.getLog(ContainerBase.class);

    /**
     * Perform addChild with the permissions of this class.
     * addChild can be called with the XML parser on the stack,
     * this allows the XML parser to have fewer privileges than
     * Tomcat.
     */
    protected class PrivilegedAddChild implements PrivilegedAction<Void> {

        private final Container child;

        PrivilegedAddChild(Container child) {
            this.child = child;
        }

        @Override
        public Void run() {
            addChildInternal(child);
            return null;
        }

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The child Containers belonging to this Container, keyed by name.
     */
    protected final HashMap<String, Container> children = new HashMap<>();


    /**
     * The processor delay for this component.
     */
    protected int backgroundProcessorDelay = -1;


    /**
     * The container event listeners for this Container. Implemented as a
     * CopyOnWriteArrayList since listeners may invoke methods to add/remove
     * themselves or other listeners and with a ReadWriteLock that would trigger
     * a deadlock.
     */
    protected final List<ContainerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * The Logger implementation with which this Container is associated.
     */
    protected Log logger = null;


    /**
     * Associated logger name.
     */
    protected String logName = null;


    /**
     * The cluster with which this Container is associated.
     */
    protected Cluster cluster = null;
    private final ReadWriteLock clusterLock = new ReentrantReadWriteLock();


    /**
     * The human-readable name of this Container.
     */
    protected String name = null;


    /**
     * The parent Container to which this Container is a child.
     */
    protected Container parent = null;


    /**
     * The parent class loader to be configured when we install a Loader.
     */
    protected ClassLoader parentClassLoader = null;


    /**
     * The Pipeline object with which this Container is associated.
     */
    protected final Pipeline pipeline = new StandardPipeline(this);


    /**
     * The Realm with which this Container is associated.
     */
    private volatile Realm realm = null;


    /**
     * Lock used to control access to the Realm.
     */
    private final ReadWriteLock realmLock = new ReentrantReadWriteLock();


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    /**
     * Will children be started automatically when they are added.
     */
    protected boolean startChildren = true;

    /**
     * The property change support for this component.
     */
    protected final PropertyChangeSupport support =
            new PropertyChangeSupport(this);


    /**
     * The background thread.
     */
    private Thread thread = null;


    /**
     * The background thread completion semaphore.
     */
    private volatile boolean threadDone = false;


    /**
     * The access log to use for requests normally handled by this container
     * that have been handled earlier in the processing chain.
     */
    protected volatile AccessLog accessLog = null;
    private volatile boolean accessLogScanComplete = false;


    /**
     * The number of threads available to process start and stop events for any
     * children associated with this container.
     */
    private int startStopThreads = 1;
    protected ThreadPoolExecutor startStopExecutor;


    // ------------------------------------------------------------- Properties

    @Override
    public int getStartStopThreads() {
        return startStopThreads;
    }

    /**
     * Handles the special values.
     */
    private int getStartStopThreadsInternal() {
        int result = getStartStopThreads();

        // Positive values are unchanged
        if (result > 0) {
            return result;
        }

        // Zero == Runtime.getRuntime().availableProcessors()
        // -ve  == Runtime.getRuntime().availableProcessors() + value
        // These two are the same
        result = Runtime.getRuntime().availableProcessors() + result;
        if (result < 1) {
            result = 1;
        }
        return result;
    }

    @Override
    public void setStartStopThreads(int startStopThreads) {
        this.startStopThreads = startStopThreads;

        // Use local copies to ensure thread safety
        ThreadPoolExecutor executor = startStopExecutor;
        if (executor != null) {
            int newThreads = getStartStopThreadsInternal();
            executor.setMaximumPoolSize(newThreads);
            executor.setCorePoolSize(newThreads);
        }
    }


    /**
     * Get the delay between the invocation of the backgroundProcess method on
     * this container and its children. Child containers will not be invoked
     * if their delay value is not negative (which would mean they are using
     * their own thread). Setting this to a positive value will cause
     * a thread to be spawn. After waiting the specified amount of time,
     * the thread will invoke the executePeriodic method on this container
     * and all its children.
     */
    @Override
    public int getBackgroundProcessorDelay() {
        return backgroundProcessorDelay;
    }


    /**
     * Set the delay between the invocation of the execute method on this
     * container and its children.
     *
     * @param delay The delay in seconds between the invocation of
     *              backgroundProcess methods
     */
    @Override
    public void setBackgroundProcessorDelay(int delay) {
        backgroundProcessorDelay = delay;
    }


    /**
     * Return the Logger for this Container.
     */
    @Override
    public Log getLogger() {
        if (logger != null)
            return logger;
        logger = LogFactory.getLog(getLogName());
        return logger;
    }


    /**
     * @return the abbreviated name of this container for logging messages
     */
    @Override
    public String getLogName() {

        if (logName != null) {
            return logName;
        }
        String loggerName = null;
        Container current = this;
        while (current != null) {
            String name = current.getName();
            if ((name == null) || (name.equals(""))) {
                name = "/";
            } else if (name.startsWith("##")) {
                name = "/" + name;
            }
            loggerName = "[" + name + "]"
                + ((loggerName != null) ? ("." + loggerName) : "");
            current = current.getParent();
        }
        logName = ContainerBase.class.getName() + "." + loggerName;
        return logName;

    }


    /**
     * Return the Cluster with which this Container is associated.  If there is
     * no associated Cluster, return the Cluster associated with our parent
     * Container (if any); otherwise return <code>null</code>.
     */
    @Override
    public Cluster getCluster() {
        Lock readLock = clusterLock.readLock();
        readLock.lock();
        try {
            if (cluster != null)
                return cluster;

            if (parent != null)
                return parent.getCluster();

            return null;
        } finally {
            readLock.unlock();
        }
    }


    /*
     * Provide access to just the cluster component attached to this container.
     */
    protected Cluster getClusterInternal() {
        Lock readLock = clusterLock.readLock();
        readLock.lock();
        try {
            return cluster;
        } finally {
            readLock.unlock();
        }
    }


    /**
     * Set the Cluster with which this Container is associated.
     *
     * @param cluster The newly associated Cluster
     */
    @Override
    public void setCluster(Cluster cluster) {

        Cluster oldCluster = null;
        Lock writeLock = clusterLock.writeLock();
        writeLock.lock();
        try {
            // Change components if necessary
            oldCluster = this.cluster;
            if (oldCluster == cluster)
                return;
            this.cluster = cluster;

            // Stop the old component if necessary
            if (getState().isAvailable() && (oldCluster != null) &&
                (oldCluster instanceof Lifecycle)) {
                try {
                    ((Lifecycle) oldCluster).stop();
                } catch (LifecycleException e) {
                    log.error("ContainerBase.setCluster: stop: ", e);
                }
            }

            // Start the new component if necessary
            if (cluster != null)
                cluster.setContainer(this);

            if (getState().isAvailable() && (cluster != null) &&
                (cluster instanceof Lifecycle)) {
                try {
                    ((Lifecycle) cluster).start();
                } catch (LifecycleException e) {
                    log.error("ContainerBase.setCluster: start: ", e);
                }
            }
        } finally {
            writeLock.unlock();
        }

        // Report this property change to interested listeners
        support.firePropertyChange("cluster", oldCluster, cluster);
    }


    /**
     * Return a name string (suitable for use by humans) that describes this
     * Container.  Within the set of child containers belonging to a particular
     * parent, Container names must be unique.
     */
    @Override
    public String getName() {
        return name;
    }


    /**
     * Set a name string (suitable for use by humans) that describes this
     * Container.  Within the set of child containers belonging to a particular
     * parent, Container names must be unique.
     *
     * @param name New name of this container
     *
     * @exception IllegalStateException if this Container has already been
     *  added to the children of a parent Container (after which the name
     *  may not be changed)
     */
    @Override
    public void setName(String name) {
        if (name == null) {
            throw new IllegalArgumentException(sm.getString("containerBase.nullName"));
        }
        String oldName = this.name;
        this.name = name;
        support.firePropertyChange("name", oldName, this.name);
    }


    /**
     * Return if children of this container will be started automatically when
     * they are added to this container.
     *
     * @return <code>true</code> if the children will be started
     */
    public boolean getStartChildren() {
        return startChildren;
    }


    /**
     * Set if children of this container will be started automatically when
     * they are added to this container.
     *
     * @param startChildren New value of the startChildren flag
     */
    public void setStartChildren(boolean startChildren) {

        boolean oldStartChildren = this.startChildren;
        this.startChildren = startChildren;
        support.firePropertyChange("startChildren", oldStartChildren, this.startChildren);
    }


    /**
     * Return the Container for which this Container is a child, if there is
     * one.  If there is no defined parent, return <code>null</code>.
     */
    @Override
    public Container getParent() {
        return parent;
    }


    /**
     * Set the parent Container to which this Container is being added as a
     * child.  This Container may refuse to become attached to the specified
     * Container by throwing an exception.
     *
     * @param container Container to which this Container is being added
     *  as a child
     *
     * @exception IllegalArgumentException if this Container refuses to become
     *  attached to the specified Container
     */
    @Override
    public void setParent(Container container) {

        Container oldParent = this.parent;
        this.parent = container;
        support.firePropertyChange("parent", oldParent, this.parent);

    }


    /**
     * Return the parent class loader (if any) for this web application.
     * This call is meaningful only <strong>after</strong> a Loader has
     * been configured.
     */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null)
            return parentClassLoader;
        if (parent != null) {
            return parent.getParentClassLoader();
        }
        return ClassLoader.getSystemClassLoader();
    }


    /**
     * Set the parent class loader (if any) for this web application.
     * This call is meaningful only <strong>before</strong> a Loader has
     * been configured, and the specified value (if non-null) should be
     * passed as an argument to the class loader constructor.
     *
     *
     * @param parent The new parent class loader
     */
    @Override
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader oldParentClassLoader = this.parentClassLoader;
        this.parentClassLoader = parent;
        support.firePropertyChange("parentClassLoader", oldParentClassLoader,
                                   this.parentClassLoader);

    }


    /**
     * Return the Pipeline object that manages the Valves associated with
     * this Container.
     */
    @Override
    public Pipeline getPipeline() {
        return this.pipeline;
    }


    /**
     * Return the Realm with which this Container is associated.  If there is
     * no associated Realm, return the Realm associated with our parent
     * Container (if any); otherwise return <code>null</code>.
     */
    @Override
    public Realm getRealm() {

        Lock l = realmLock.readLock();
        l.lock();
        try {
            if (realm != null)
                return realm;
            if (parent != null)
                return parent.getRealm();
            return null;
        } finally {
            l.unlock();
        }
    }


    protected Realm getRealmInternal() {
        Lock l = realmLock.readLock();
        l.lock();
        try {
            return realm;
        } finally {
            l.unlock();
        }
    }

    /**
     * Set the Realm with which this Container is associated.
     *
     * @param realm The newly associated Realm
     */
    @Override
    public void setRealm(Realm realm) {

        Lock l = realmLock.writeLock();
        l.lock();
        try {
            // Change components if necessary
            Realm oldRealm = this.realm;
            if (oldRealm == realm)
                return;
            this.realm = realm;

            // Stop the old component if necessary
            if (getState().isAvailable() && (oldRealm != null) &&
                (oldRealm instanceof Lifecycle)) {
                try {
                    ((Lifecycle) oldRealm).stop();
                } catch (LifecycleException e) {
                    log.error("ContainerBase.setRealm: stop: ", e);
                }
            }

            // Start the new component if necessary
            if (realm != null)
                realm.setContainer(this);
            if (getState().isAvailable() && (realm != null) &&
                (realm instanceof Lifecycle)) {
                try {
                    ((Lifecycle) realm).start();
                } catch (LifecycleException e) {
                    log.error("ContainerBase.setRealm: start: ", e);
                }
            }

            // Report this property change to interested listeners
            support.firePropertyChange("realm", oldRealm, this.realm);
        } finally {
            l.unlock();
        }

    }


    // ------------------------------------------------------ Container Methods


    /**
     * Add a new child Container to those associated with this Container,
     * if supported.  Prior to adding this Container to the set of children,
     * the child's <code>setParent()</code> method must be called, with this
     * Container as an argument.  This method may thrown an
     * <code>IllegalArgumentException</code> if this Container chooses not
     * to be attached to the specified Container, in which case it is not added
     *
     * @param child New child Container to be added
     *
     * @exception IllegalArgumentException if this exception is thrown by
     *  the <code>setParent()</code> method of the child Container
     * @exception IllegalArgumentException if the new child does not have
     *  a name unique from that of existing children of this Container
     * @exception IllegalStateException if this Container does not support
     *  child Containers
     */
    @Override
    public void addChild(Container child) {
        if (Globals.IS_SECURITY_ENABLED) {
            PrivilegedAction<Void> dp =
                new PrivilegedAddChild(child);
            AccessController.doPrivileged(dp);
        } else {
            addChildInternal(child);
        }
    }

    private void addChildInternal(Container child) {

        if( log.isDebugEnabled() )
            log.debug("Add child " + child + " " + this);
        synchronized(children) {
            if (children.get(child.getName()) != null)
                throw new IllegalArgumentException("addChild:  Child name '" +
                                                   child.getName() +
                                                   "' is not unique");
            child.setParent(this);  // May throw IAE
            children.put(child.getName(), child);
        }

        // Start child
        // Don't do this inside sync block - start can be a slow process and
        // locking the children object can cause problems elsewhere
        try {
            if ((getState().isAvailable() ||
                    LifecycleState.STARTING_PREP.equals(getState())) &&
                    startChildren) {
                child.start();
            }
        } catch (LifecycleException e) {
            log.error("ContainerBase.addChild: start: ", e);
            throw new IllegalStateException("ContainerBase.addChild: start: " + e);
        } finally {
            fireContainerEvent(ADD_CHILD_EVENT, child);
        }
    }


    /**
     * Add a container event listener to this component.
     *
     * @param listener The listener to add
     */
    @Override
    public void addContainerListener(ContainerListener listener) {
        listeners.add(listener);
    }


    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     */
    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }


    /**
     * Return the child Container, associated with this Container, with
     * the specified name (if any); otherwise, return <code>null</code>
     *
     * @param name Name of the child Container to be retrieved
     */
    @Override
    public Container findChild(String name) {
        if (name == null) {
            return null;
        }
        synchronized (children) {
            return children.get(name);
        }
    }


    /**
     * Return the set of children Containers associated with this Container.
     * If this Container has no children, a zero-length array is returned.
     */
    @Override
    public Container[] findChildren() {
        synchronized (children) {
            Container results[] = new Container[children.size()];
            return children.values().toArray(results);
        }
    }


    /**
     * Return the set of container listeners associated with this Container.
     * If this Container has no registered container listeners, a zero-length
     * array is returned.
     */
    @Override
    public ContainerListener[] findContainerListeners() {
        ContainerListener[] results =
            new ContainerListener[0];
        return listeners.toArray(results);
    }


    /**
     * Remove an existing child Container from association with this parent
     * Container.
     *
     * @param child Existing child Container to be removed
     */
    @Override
    public void removeChild(Container child) {

        if (child == null) {
            return;
        }

        try {
            if (child.getState().isAvailable()) {
                child.stop();
            }
        } catch (LifecycleException e) {
            log.error("ContainerBase.removeChild: stop: ", e);
        }

        try {
            // child.destroy() may have already been called which would have
            // triggered this call. If that is the case, no need to destroy the
            // child again.
            if (!LifecycleState.DESTROYING.equals(child.getState())) {
                child.destroy();
            }
        } catch (LifecycleException e) {
            log.error("ContainerBase.removeChild: destroy: ", e);
        }

        synchronized(children) {
            if (children.get(child.getName()) == null)
                return;
            children.remove(child.getName());
        }

        fireContainerEvent(REMOVE_CHILD_EVENT, child);
    }


    /**
     * Remove a container event listener from this component.
     *
     * @param listener The listener to remove
     */
    @Override
    public void removeContainerListener(ContainerListener listener) {
        listeners.remove(listener);
    }


    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     */
    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {

        support.removePropertyChangeListener(listener);

    }

    /**
     *  子类Engine实际调用的父类方法。
     *  1. 创建了起停线程池。(将Engine启动交给线程池.)
     *  并没有对后续容器做初始化操作。此时初始化过程就完成了。所以其他的容器是在start阶段启动的。
     * @throws LifecycleException
     */
    @Override
    protected void initInternal() throws LifecycleException {
        BlockingQueue<Runnable> startStopQueue = new LinkedBlockingQueue<>();
        startStopExecutor = new ThreadPoolExecutor(
                getStartStopThreadsInternal(),
                getStartStopThreadsInternal(), 10, TimeUnit.SECONDS,
                startStopQueue,
                new StartStopThreadFactory(getName() + "-startStop-"));
        startStopExecutor.allowCoreThreadTimeOut(true);
        super.initInternal();
    }


    /**
     1.BootStrap反射调用Catalina的start。
     * 2.Catalina去调用了StandardServer的start方法。
     * 3.StandardServer调用StandardService的start方法。
     * 4.StandardService调用StandarEngine的start方法。
     * 5.StandarEngine调用ContainerBase的start方法。(重点)
     * 6.ContainerBase的start方法：
     *          6.1 日志。
     *          6.2 安全。
     *          6.3 启动所有子容器(ChildList Future框架启动线程池去启动子结点)。启动子结点重点。
     *                  (Engin -> Host -> Context-> Wrapper 层级调用。
     *                      共享父类的方法，针对不同的容器处理不同。)
     *                      HostConfig类添加所有的Context结点。
     *                      然后在此类中进行启动。包括StandardContext结点。
     *                      StandardContext结点通过Web.xml文件，用ContextConfig添加了
     *                      所有的子节点。
     *                      然后在StandardContext去启动了StandardWrapper。
     *                      StandardWrapper load and start。
     *          6.4 Pipeline的启动。
     *          6.5 激发HostConfig监听器。
     *          (HostConfig ->t添加Host。) {@link HostConfig#start()}
     *          6.6 启动后台线程。
     *
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        // Start our subordinate components, if any
        logger = null;
        getLogger();
        //tomcat集群相关。
        Cluster cluster = getClusterInternal();
        if (cluster instanceof Lifecycle) {
            ((Lifecycle) cluster).start();
        }
        Realm realm = getRealmInternal();
        if (realm instanceof Lifecycle) {
            ((Lifecycle) realm).start();
        }

        // Start our child containers, if any
        Container children[] = findChildren();
        List<Future<Void>> results = new ArrayList<>();
        for (int i = 0; i < children.length; i++) {
            results.add(startStopExecutor.submit(new StartChild(children[i])));
        }

        MultiThrowable multiThrowable = null;

        for (Future<Void> result : results) {
            try {
                result.get();
            } catch (Throwable e) {
                log.error(sm.getString("containerBase.threadedStartFailed"), e);
                if (multiThrowable == null) {
                    multiThrowable = new MultiThrowable();
                }
                multiThrowable.add(e);
            }

        }
        if (multiThrowable != null) {
            throw new LifecycleException(sm.getString("containerBase.threadedStartFailed"),
                    multiThrowable.getThrowable());
        }

        // Start the Valves in our pipeline (including the basic), if any
        if (pipeline instanceof Lifecycle) {
            ((Lifecycle) pipeline).start();
        }


        setState(LifecycleState.STARTING);

        // Start our thread
        threadStart();
    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        // Stop our thread
        threadStop();

        setState(LifecycleState.STOPPING);

        // Stop the Valves in our pipeline (including the basic), if any
        if (pipeline instanceof Lifecycle &&
                ((Lifecycle) pipeline).getState().isAvailable()) {
            ((Lifecycle) pipeline).stop();
        }

        // Stop our child containers, if any
        Container children[] = findChildren();
        List<Future<Void>> results = new ArrayList<>();
        for (int i = 0; i < children.length; i++) {
            results.add(startStopExecutor.submit(new StopChild(children[i])));
        }

        boolean fail = false;
        for (Future<Void> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("containerBase.threadedStopFailed"), e);
                fail = true;
            }
        }
        if (fail) {
            throw new LifecycleException(
                    sm.getString("containerBase.threadedStopFailed"));
        }

        // Stop our subordinate components, if any
        Realm realm = getRealmInternal();
        if (realm instanceof Lifecycle) {
            ((Lifecycle) realm).stop();
        }
        Cluster cluster = getClusterInternal();
        if (cluster instanceof Lifecycle) {
            ((Lifecycle) cluster).stop();
        }
    }

    @Override
    protected void destroyInternal() throws LifecycleException {

        Realm realm = getRealmInternal();
        if (realm instanceof Lifecycle) {
            ((Lifecycle) realm).destroy();
        }
        Cluster cluster = getClusterInternal();
        if (cluster instanceof Lifecycle) {
            ((Lifecycle) cluster).destroy();
        }

        // Stop the Valves in our pipeline (including the basic), if any
        if (pipeline instanceof Lifecycle) {
            ((Lifecycle) pipeline).destroy();
        }

        // Remove children now this container is being destroyed
        for (Container child : findChildren()) {
            removeChild(child);
        }

        // Required if the child is destroyed directly.
        if (parent != null) {
            parent.removeChild(this);
        }

        // If init fails, this may be null
        if (startStopExecutor != null) {
            startStopExecutor.shutdownNow();
        }

        super.destroyInternal();
    }


    /**
     * Check this container for an access log and if none is found, look to the
     * parent. If there is no parent and still none is found, use the NoOp
     * access log.
     */
    @Override
    public void logAccess(Request request, Response response, long time,
            boolean useDefault) {

        boolean logged = false;

        if (getAccessLog() != null) {
            getAccessLog().log(request, response, time);
            logged = true;
        }

        if (getParent() != null) {
            // No need to use default logger once request/response has been logged
            // once
            getParent().logAccess(request, response, time, (useDefault && !logged));
        }
    }

    @Override
    public AccessLog getAccessLog() {

        if (accessLogScanComplete) {
            return accessLog;
        }

        AccessLogAdapter adapter = null;
        Valve valves[] = getPipeline().getValves();
        for (Valve valve : valves) {
            if (valve instanceof AccessLog) {
                if (adapter == null) {
                    adapter = new AccessLogAdapter((AccessLog) valve);
                } else {
                    adapter.add((AccessLog) valve);
                }
            }
        }
        if (adapter != null) {
            accessLog = adapter;
        }
        accessLogScanComplete = true;
        return accessLog;
    }

    // ------------------------------------------------------- Pipeline Methods


    /**
     * Convenience method, intended for use by the digester to simplify the
     * process of adding Valves to containers. See
     * {@link Pipeline#addValve(Valve)} for full details. Components other than
     * the digester should use {@link #getPipeline()}.{@link #addValve(Valve)} in case a
     * future implementation provides an alternative method for the digester to
     * use.
     *
     * @param valve Valve to be added
     *
     * @exception IllegalArgumentException if this Container refused to
     *  accept the specified Valve
     * @exception IllegalArgumentException if the specified Valve refuses to be
     *  associated with this Container
     * @exception IllegalStateException if the specified Valve is already
     *  associated with a different Container
     */
    public synchronized void addValve(Valve valve) {

        pipeline.addValve(valve);
    }


    /**
     * Execute a periodic task, such as reloading, etc. This method will be
     * invoked inside the classloading context of this container. Unexpected
     * throwables will be caught and logged.
     */
    @Override
    public void backgroundProcess() {

        if (!getState().isAvailable())
            return;

        Cluster cluster = getClusterInternal();
        if (cluster != null) {
            try {
                cluster.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.cluster",
                        cluster), e);
            }
        }
        Realm realm = getRealmInternal();
        if (realm != null) {
            try {
                realm.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.realm", realm), e);
            }
        }
        // 管道实现关键
        // 如果已经有first，则遍历整个valve链表，一直遍历到basic为止
        // 在basic之前添加该valve
        // 无论如何都保证了basic是整个pipeline为最后一个valve
        // 我们可以通过addValve方法总结出pipeline中valve的排列规则
        //
        // 整个是按照链表来排列的，顺序添加，第一个添加的valve放在first位置，第二个放在first后面...以此类推
        //
        // 最后一个valve是固定的，默认就是basic，其他的valve都放在basic之前

        Valve current = pipeline.getFirst();
        while (current != null) {
            try {
                current.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("containerBase.backgroundProcess.valve", current), e);
            }
            current = current.getNext();
        }
        fireLifecycleEvent(Lifecycle.PERIODIC_EVENT, null);
    }


    @Override
    public File getCatalinaBase() {

        if (parent == null) {
            return null;
        }

        return parent.getCatalinaBase();
    }


    @Override
    public File getCatalinaHome() {

        if (parent == null) {
            return null;
        }

        return parent.getCatalinaHome();
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Notify all container event listeners that a particular event has
     * occurred for thiser.  The default implementation performs
     * this notification synchronously using the calling thread.
     *
     * @param type Event type
     * @param data Event data
     */
    @Override
    public void fireContainerEvent(String type, Object data) {

        if (listeners.size() < 1)
            return;

        ContainerEvent event = new ContainerEvent(this, type, data);
        // Note for each uses an iterator internally so this is safe
        for (ContainerListener listener : listeners) {
            listener.containerEvent(event);
        }
    }


    // -------------------- JMX and Registration  --------------------

    @Override
    protected String getDomainInternal() {

        Container p = this.getParent();
        if (p == null) {
            return null;
        } else {
            return p.getDomain();
        }
    }


    @Override
    public String getMBeanKeyProperties() {
        Container c = this;
        StringBuilder keyProperties = new StringBuilder();
        int containerCount = 0;

        // Work up container hierarchy, add a component to the name for
        // each container
        while (!(c instanceof Engine)) {
            if (c instanceof Wrapper) {
                keyProperties.insert(0, ",servlet=");
                keyProperties.insert(9, c.getName());
            } else if (c instanceof Context) {
                keyProperties.insert(0, ",context=");
                ContextName cn = new ContextName(c.getName(), false);
                keyProperties.insert(9,cn.getDisplayName());
            } else if (c instanceof Host) {
                keyProperties.insert(0, ",host=");
                keyProperties.insert(6, c.getName());
            } else if (c == null) {
                // May happen in unit testing and/or some embedding scenarios
                keyProperties.append(",container");
                keyProperties.append(containerCount++);
                keyProperties.append("=null");
                break;
            } else {
                // Should never happen...
                keyProperties.append(",container");
                keyProperties.append(containerCount++);
                keyProperties.append('=');
                keyProperties.append(c.getName());
            }
            c = c.getParent();
        }
        return keyProperties.toString();
    }

    public ObjectName[] getChildren() {
        List<ObjectName> names = new ArrayList<>(children.size());
        for (Container next : children.values()) {
            if (next instanceof ContainerBase) {
                names.add(((ContainerBase)next).getObjectName());
            }
        }
        return names.toArray(new ObjectName[names.size()]);
    }


    // -------------------- Background Thread --------------------

    /**
     * Start the background thread that will periodically check for
     * session timeouts.
     */
    protected void threadStart() {

        if (thread != null)
            return;
        if (backgroundProcessorDelay <= 0)
            return;

        threadDone = false;
        String threadName = "ContainerBackgroundProcessor[" + toString() + "]";
        thread = new Thread(new ContainerBackgroundProcessor(), threadName);
        thread.setDaemon(true);
        /**
         *
         * ContainerBackgroundProcessor[StandardEngine[Catalina]]
         * {@link ContainerBase#threadStart()}
         */
        thread.start();

    }


    /**
     * Stop the background thread that is periodically checking for
     * session timeouts.
     */
    protected void threadStop() {

        if (thread == null)
            return;

        threadDone = true;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            // Ignore
        }

        thread = null;

    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Container parent = getParent();
        if (parent != null) {
            sb.append(parent.toString());
            sb.append('.');
        }
        sb.append(this.getClass().getSimpleName());
        sb.append('[');
        sb.append(getName());
        sb.append(']');
        return sb.toString();
    }


    // -------------------------------------- ContainerExecuteDelay Inner Class

    /**
     * Private thread class to invoke the backgroundProcess method
     * of this container and its children after a fixed delay.
     */
    protected class ContainerBackgroundProcessor implements Runnable {

        @Override
        public void run() {
            Throwable t = null;
            String unexpectedDeathMessage = sm.getString(
                    "containerBase.backgroundProcess.unexpectedThreadDeath",
                    Thread.currentThread().getName());
            try {
                while (!threadDone) {
                    try {
                        Thread.sleep(backgroundProcessorDelay * 1000L);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                    if (!threadDone) {
                        processChildren(ContainerBase.this);
                    }
                }
            } catch (RuntimeException|Error e) {
                t = e;
                throw e;
            } finally {
                if (!threadDone) {
                    log.error(unexpectedDeathMessage, t);
                }
            }
        }

        protected void processChildren(Container container) {
            ClassLoader originalClassLoader = null;

            try {
                if (container instanceof Context) {
                    Loader loader = ((Context) container).getLoader();
                    // Loader will be null for FailedContext instances
                    if (loader == null) {
                        return;
                    }

                    // Ensure background processing for Contexts and Wrappers
                    // is performed under the web app's class loader
                    originalClassLoader = ((Context) container).bind(false, null);
                }
                container.backgroundProcess();
                Container[] children = container.findChildren();
                for (int i = 0; i < children.length; i++) {
                    if (children[i].getBackgroundProcessorDelay() <= 0) {
                        processChildren(children[i]);
                    }
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("Exception invoking periodic operation: ", t);
            } finally {
                if (container instanceof Context) {
                    ((Context) container).unbind(false, originalClassLoader);
               }
            }
        }
    }


    // ----------------------------- Inner classes used with start/stop Executor

    private static class StartChild implements Callable<Void> {

        private Container child;

        public StartChild(Container child) {
            this.child = child;
        }

        @Override
        public Void call() throws LifecycleException {
            child.start();
            return null;
        }
    }

    private static class StopChild implements Callable<Void> {

        private Container child;

        public StopChild(Container child) {
            this.child = child;
        }

        @Override
        public Void call() throws LifecycleException {
            if (child.getState().isAvailable()) {
                child.stop();
            }
            return null;
        }
    }

    private static class StartStopThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        public StartStopThreadFactory(String namePrefix) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(group, r, namePrefix + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
