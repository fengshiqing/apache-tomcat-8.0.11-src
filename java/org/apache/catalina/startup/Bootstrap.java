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
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * 功能描述：tomcat的start.bat/start.sh启动时，就调用这个类中的 main 方法。
 *
 * Bootstrap loader for Catalina. This application constructs a class loader for use in loading the Catalina internal
 * classes (by accumulating all of the JAR files found in the "server" directory under "catalina.home"), and starts the
 * regular execution of the container. The purpose of this roundabout approach is to keep the Catalina internal classes
 * (and any other classes they depend on, such as an XML parser) out of the system class path and therefore not visible
 * to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static Bootstrap daemon = null;

    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;

    private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");

    // 静态代码块，设置系统属性 catalina.home 的目录，可以通过VM参数 -Dcatalina.home=catalina-home 设置。
    // 没有设置就以 System.getProperty("user.dir") 为准。
    static {
        // Will always be non-null
        String userDir = System.getProperty("user.dir"); // 获取当前程序运行的目录路径

        // Home first
        String home = System.getProperty(Globals.CATALINA_HOME_PROP);
        File homeFile = null;

        if (home != null) {
            File file = new File(home);
            try {
                homeFile = file.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = file.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory in a normal Tomcat install
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File file = new File(userDir);
            try {
                homeFile = file.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = file.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath()); // 处理VM参数：-Dcatalina.home=catalina-home

        // Then base
        String base = System.getProperty(Globals.CATALINA_BASE_PROP);
        if (base == null) {
            catalinaBaseFile = catalinaHomeFile;
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath()); // 处理VM参数：-Dcatalina.base=catalina-home
    }

    // -------------------------------------------------------------- Variables

    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;

    private ClassLoader catalinaLoader = null;
    private ClassLoader sharedLoader = null;

    // -------------------------------------------------------- Private Methods

    /**
     * 功能描述：初始化类加载器
     */
    private void initClassLoaders() {
        try {
            ClassLoader commonLoader = this.createClassLoader("common", null);
            if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader = this.getClass().getClassLoader();
            }
            catalinaLoader = this.createClassLoader("server", commonLoader);
            sharedLoader = this.createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }

    private ClassLoader createClassLoader(String name, ClassLoader parent) throws Exception {

        String value = CatalinaProperties.getProperty(name + ".loader"); // 获取 catalina.properties配置文件中 common.loader的值
        if ((value == null) || (value.equals("")))
            return parent;

        value = replace(value);

        List<Repository> repositories = new ArrayList<>();

        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring(0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }

    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     *
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        log.info("【从catalina.properties中获取属性，4个目录：】" + result);
        return result;
    }

    /**
     * Initialize daemon. 初始化 catalinaDaemon
     */
    public void init() throws Exception {
        this.initClassLoaders(); // tomcat自定义了一个类加载器，

        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        log.info("【Loading startup class: org.apache.catalina.startup.Catalina】");
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.newInstance();

        log.info("Setting startup class properties");
        // Set the shared extensions class loader
        String methodName = "setParentClassLoader";
        Class<?>[] paramTypes = new Class[] { Class.forName("java.lang.ClassLoader") };
        Method method = startupInstance.getClass().getMethod(methodName, paramTypes);
        Object[] paramValues = new Object[] { sharedLoader };
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;
    }

    /**
     * Load daemon.
     */
    private void load(String[] arguments) throws Exception {

        // Call the load() method
        String methodName = "load";
        Object[] param;
        Class<?>[] paramTypes;
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method = catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled())
            log.debug("Calling startup class " + method);
        method.invoke(catalinaDaemon, param);

    }

    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {
        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }

    // ----------------------------------------------------------- Main Program

    /**
     * Load the Catalina daemon.
     */
    public void init(String[] arguments) throws Exception {
        init();
        load(arguments);
    }

    /**
     * Start the Catalina daemon.
     */
    public void start() throws Exception {
        if (catalinaDaemon == null)
            init();
        Method method = catalinaDaemon.getClass().getMethod("start");
        method.invoke(catalinaDaemon, (Object[]) null);
    }

    /**
     * Stop the Catalina Daemon.
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop");
        method.invoke(catalinaDaemon, (Object[]) null);
    }

    /**
     * Stop the standalone server.
     */
    public void stopServer() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stopServer");
        method.invoke(catalinaDaemon, (Object[]) null);
    }

    /**
     * Stop the standalone server.
     */
    public void stopServer(String[] arguments) throws Exception {
        Object[] param;
        Class<?>[] paramTypes;
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method = catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }

    /**
     * Set flag.
     */
    public void setAwait(boolean await) throws Exception {
        Class<?>[] paramTypes = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object[] paramValues = new Object[1];
        paramValues[0] = await;
        Method method = catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?>[] paramTypes = new Class[0];
        Object[] paramValues = new Object[0];
        Method method = catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        return (Boolean) method.invoke(catalinaDaemon, paramValues);
    }

    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {
        // FIXME
    }

    /**
     * Main method and entry point when starting Tomcat via the provided scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String[] args) {
        if (daemon == null) {
            // Don't set daemon until init() has completed
            Bootstrap bootstrap = new Bootstrap();
            try {
                bootstrap.init();
            } catch (Throwable t) {
                handleThrowable(t);
                t.printStackTrace();
                return;
            }
            daemon = bootstrap; // daemon必须初始化，初始化失败就结束进程
        } else {
            // When running as a service the call to stop will be on a new thread so make sure the correct class loader is used to prevent
            // a range of class not found exceptions.
            Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
        }

        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            switch (command) {
                case "startd":
                    args[args.length - 1] = "start";
                    daemon.load(args);
                    daemon.start();
                    break;
                case "stopd":
                    args[args.length - 1] = "stop";
                    daemon.stop();
                    break;
                case "start":
                    daemon.setAwait(true);
                    daemon.load(args); // 核心方法
                    daemon.start(); // 核心方法
                    break;
                case "stop":
                    daemon.stopServer(args);
                    break;
                case "configtest":
                    daemon.load(args);
                    if (null == daemon.getServer()) {
                        System.exit(1);
                    }
                    System.exit(0);
                default:
                    log.warn("Bootstrap: command \"" + command + "\" does not exist.");
                    break;
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException && t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Obtain the name of configured home (binary) directory. Note that home and base may be the same (and are by
     * default).
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }

    /**
     * Obtain the name of the configured base (instance) directory. Note that home and base may be the same (and are by
     * default). If this is not set the value returned by {@link #getCatalinaHome()} will be used.
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }

    /**
     * Obtain the configured home (binary) directory. Note that home and base may be the same (and are by default).
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }

    /**
     * Obtain the configured base (instance) directory. Note that home and base may be the same (and are by default). If
     * this is not set the value returned by {@link #getCatalinaHomeFile()} will be used.
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }

    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }

    // Protected for unit testing
    protected static String[] getPaths(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();

            if (path.startsWith("\"") && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
            }

            if (path.length() == 0) {
                continue;
            }

            result.add(path);
        }
        return result.toArray(new String[result.size()]);
    }
}
