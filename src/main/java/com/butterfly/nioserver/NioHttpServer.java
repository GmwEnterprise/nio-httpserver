package com.butterfly.nioserver;

import com.butterfly.nioserver.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

/**
 * 基于NIO的HTTP服务器类
 */
public class NioHttpServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(NioHttpServer.class);

    private final ServerSocketChannel serverChannel;
    private final Selector selector;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(1024);
    private final List<ChangeRequest> changeRequests = new LinkedList<>();
    private final Map<SocketChannel, List<ByteBuffer>> pendingSentMap = new HashMap<>();
    private final List<RequestHandler> requestHandlers = new ArrayList<>();

    public NioHttpServer(InetAddress address, int port) throws IOException {
        // 初始化serverChannel并绑定IP和端口
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(address, port));

        // 初始化选择器并注册serverChannel的ACCEPT事件
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    /**
     * 启动服务
     */
    @Override
    public void run() {
        for (SelectionKey key = null; ; ) {
            try {
                synchronized (changeRequests) {
                    for (ChangeRequest request : changeRequests) {
                        switch (request.type) {
                            case ChangeRequest.CHANGE_OPS:
                                // 获取channel注册在指定selector上的事件SelectionKey
                                key = request.socketChannel.keyFor(selector);
                                if (key != null && key.isValid()) {
                                    // 替换事件
                                    key.interestOps(request.ops);
                                }
                                break;
                            case ChangeRequest.REGISTER:
                            default:
                                // 其他情况不做处理
                        }
                    }
                    // 处理完事件变换请求后清空请求列表
                    changeRequests.clear();
                }

                // 阻塞等待至少一个事件触发
                selector.select();

                // 获取事件keys
                Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    key = selectedKeys.next();
                    selectedKeys.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        // 处理新套接字连接
                        accept();
                    } else if (key.isReadable()) {
                        // 处理读事件
                        read(key);
                    } else if (key.isWritable()) {
                        // 处理写事件
                        write(key);
                    }
                }
            } catch (Exception e) {
                if (key != null) {
                    key.cancel();
                    // 发生异常则关闭对应套接字
                    Utils.closeQuietly(key.channel());
                    logger.error("closed {}: {}", key.channel(), Utils.errorStack(e));
                }
            }
        }
    }

    public void addRequestHandler(RequestHandler handler) {
        requestHandlers.add(handler);
    }

    /**
     * 处理新连接事件
     * <p>
     * 配置新的套接字通道为非阻塞，并注册到Selector
     */
    private void accept() throws IOException {
        SocketChannel socketChannel = serverChannel.accept();
        if (logger.isDebugEnabled())
            logger.debug("new connection: {}", socketChannel);
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_READ);
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();

        // 调整指针，准备写入readBuffer
        readBuffer.clear();
        int readCount;
        try {
            readCount = channel.read(readBuffer);
        } catch (IOException e) {
            // 远程节点强制关闭了套接字
            channel.close();
            key.cancel();
            logger.info("[{}] closed by exception: {}", channel, e.getMessage());
            return;
        }

        if (readCount == -1) {
            // 远程节点正常关闭了套接字
            channel.close();
            key.cancel();
            logger.info("closed by shutdown" + channel);
            return;
        }

        // 简单的负载均衡
        int workerId = channel.hashCode() % requestHandlers.size();
        if (logger.isDebugEnabled())
            logger.debug("{} - {} - {}", selector.keys().size(), workerId, channel);

        // 选中的处理器去处理读取到的数据
        requestHandlers.get(workerId).processData(channel, readBuffer.array(), readCount);
    }

    /**
     * 处理写事件
     */
    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        synchronized (pendingSentMap) {
            List<ByteBuffer> queue = pendingSentMap.get(channel);
            while (!queue.isEmpty()) {
                ByteBuffer buf = queue.get(0);
                channel.write(buf);
                // have more to send
                if (buf.remaining() > 0) {
                    break;
                }
                queue.remove(0);
            }
            if (queue.isEmpty()) {
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    public void send(SocketChannel channel, byte[] data) {
        synchronized (changeRequests) {
            changeRequests.add(new ChangeRequest(channel, ChangeRequest.CHANGE_OPS, SelectionKey.OP_WRITE));
            synchronized (pendingSentMap) {
                List<ByteBuffer> queue = pendingSentMap.computeIfAbsent(channel, k -> new LinkedList<>());
                queue.add(ByteBuffer.wrap(data));
            }
        }
        selector.wakeup();
    }
}
