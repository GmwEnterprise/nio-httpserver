package com.butterfly.nioserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public final class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws IOException {

        // 端口号
        int port = 8080;
        if (args.length > 0)
            port = Integer.parseInt(args[0]);

        // 文件访问根目录
        String root = new File(".").getCanonicalPath();
        if (args.length > 1)
            root = args[1];
        if (logger.isDebugEnabled())
            logger.debug("Root = {}, port = {}", root, port);

        // 服务实例
        NioHttpServer server = new NioHttpServer(null, port);

        // cpu核心数
        int cpu = Runtime.getRuntime().availableProcessors();

        // 缓存实例
        ButterflySoftCache cache = new ButterflySoftCache();

        // 根据cpu核心数来启动worker线程
        for (int i = 0; i < cpu; ++i) {
            // 请求handler实例
            RequestHandler handler = new RequestHandler(server, root, cache);
            server.addRequestHandler(handler);
            new Thread(handler, "nio-server-worker-" + i).start();
        }

        // 启动一个选择器/管理线程
        new Thread(server, "nio-server-selector").start();
    }
}
