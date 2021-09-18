package com.butterfly.nioserver;

import java.util.concurrent.TimeUnit;

public final class AppTest {

    static boolean stop = false;

    public static void main(String[] args) throws InterruptedException {
        Thread task = new Thread(() -> {
            int i = 0;
            while (!stop) i++;
            System.out.println("In task thread i = " + i);
        });

        task.start();
        TimeUnit.MILLISECONDS.sleep(300L);
        stop = true;
        System.out.println("Now in main thread stop = true");
        task.join();
    }
}
