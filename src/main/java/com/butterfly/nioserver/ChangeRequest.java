package com.butterfly.nioserver;

import java.nio.channels.SocketChannel;

public final class ChangeRequest {

    public static final int REGISTER = 1;   // 注册
    public static final int CHANGE_OPS = 2; // 替换事件

    public SocketChannel socketChannel;
    public int type;
    public int ops;

    public ChangeRequest(SocketChannel socketChannel, int type, int ops) {
        this.socketChannel = socketChannel;
        this.type = type;
        this.ops = ops;
    }
}
