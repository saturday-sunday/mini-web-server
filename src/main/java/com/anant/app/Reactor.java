package com.anant.app;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class Reactor implements Runnable {
    static final String usageMessage = "Usage: java -cp < path of HTTPServer-1.0-SNAPSHOT.jar > com.anant.app.Reactor < port > < root folder path >";
    final Selector selector;
    final ServerSocketChannel serverChannel;
    static final int WORKER_POOL_SIZE = 10;
    static ExecutorService workerPool;
    int port;
    File rootFolder;
    
    Reactor(int iPort, File iRootFolder) throws IOException {
        port = iPort;
        rootFolder = iRootFolder;
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.socket().bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);

        // Register the server socket channel with interest-set set to ACCEPT operation
        SelectionKey sk = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        sk.attach(new Acceptor());
    }

    public void run() {
        try {
            while (true) {

                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();

                while (it.hasNext()) {
                    SelectionKey sk = (SelectionKey) it.next();
                    it.remove();
                    Runnable r = (Runnable) sk.attachment();
                    if (r != null)
                        r.run();
                }
            }
        }
        catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    class Acceptor implements Runnable {
        public void run() {
            try {
                SocketChannel channel = serverChannel.accept();
                if (channel != null)
                    new HTTPRequestHandler(selector, channel, rootFolder);
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        if(args.length != 2 || args[0] == null || args[1] == null)
        {
            System.out.println(usageMessage);
            return;
        }
        int port = Integer.parseInt(args[0]);
        String rootPath = args[1].trim();
        File rootFolder = new File(rootPath);
        if(!rootFolder.isDirectory())
        {
            System.out.println("Error: Invlaid root folder provided.");
            System.out.println(usageMessage);
            return;
        }
        System.out.println("Serevr started. Listening on port " + port + ". Root folder is set to " + rootPath);
        workerPool = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
        
        try {
            new Thread(new Reactor(port, rootFolder)).start();
        }
        catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
