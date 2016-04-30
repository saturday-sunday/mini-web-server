package com.anant.app;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

class HTTPRequestHandler implements Runnable {
    final SocketChannel channel;
    final SelectionKey selKey;
    File _rootDir ;
    
    static final int READ_BUF_SIZE = 10240;
    static final int WRITE_BUF_SIZE = 10240;
    ByteBuffer readBuf = ByteBuffer.allocate(READ_BUF_SIZE);
    ByteBuffer writeBuf = ByteBuffer.allocate(WRITE_BUF_SIZE);
    Boolean remaining = false;
    int marker = 0;
    InputStream reader = null;
    byte[] fileBuffer = new byte[WRITE_BUF_SIZE];
    long fileSize = 0;
    int fileBufferContentSize = 0;
    
    public static final String VERSION = "Http Server 0.1";
    public static final Hashtable<String,String> MIME_TYPES = new Hashtable<String,String>();

    static {
        String image = "image/";
        MIME_TYPES.put(".gif", image + "gif");
        MIME_TYPES.put(".jpg", image + "jpeg");
        MIME_TYPES.put(".jpeg", image + "jpeg");
        MIME_TYPES.put(".png", image + "png");
        String text = "text/";
        MIME_TYPES.put(".html", text + "html");
        MIME_TYPES.put(".htm", text + "html");
        MIME_TYPES.put(".txt", text + "plain");
    }

    HTTPRequestHandler(Selector sel, SocketChannel sc, File iRootFolder) throws IOException {
        channel = sc;
        channel.configureBlocking(false);
        _rootDir = iRootFolder;
        // Register the socket channel with interest-set set to READ operation
        selKey = channel.register(sel, SelectionKey.OP_READ);
        selKey.attach(this);
        selKey.interestOps(SelectionKey.OP_READ);
        sel.wakeup();
    }
    // Work out the filename extension.  If there isn't one, we keep
    // it as the empty string ("").
    public static String getExtension(java.io.File file) {
        String extension = "";
        String filename = file.getName();
        int dotPos = filename.lastIndexOf(".");
        if (dotPos >= 0) {
            extension = filename.substring(dotPos);
        }
        return extension.toLowerCase();
    }
    
    private static String GetHTTPHeader(int code, String contentType, long contentLength, long lastModified) throws IOException {
        return "HTTP/1.0 " + code + " OK\r\n" + 
                   "Date: " + new Date().toString() + "\r\n" +
                   "Server: HTTPServer/1.0\r\n" +
                   "Content-Type: " + contentType + "\r\n" +
                   "Expires: Thu, 01 Dec 1994 16:00:00 GMT\r\n" +
                   ((contentLength != -1) ? "Content-Length: " + contentLength + "\r\n" : "") +
                   "Last-modified: " + new Date(lastModified).toString() + "\r\n" +
                   "\r\n";
    }
    
    private static String GetErrorMessage( int code, String message) throws IOException {
        return GetHTTPHeader(code, "text/html", message.length(), System.currentTimeMillis()) + message + "<hr>" + VERSION;
    }
    
    public void run() {
        try {
            if (selKey.isReadable())
                read();
            else if (selKey.isWritable())
                write();
        }
        catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    //Read the fd for incoming request, 
    //parse the request to get the path,
    //provide essential error handling
    //if requested path is a folder, create the index page with files contained in the folder.
    //if requested path is a file, write header to the fd and delegate the file content transfer to FillBuffer function.
    //TODO: create separate classes for HTTPRequest, HTTPResponse.
    //TODO: add chunk based file transfer for large files.
    synchronized void process() {
        byte[] bytes;

        readBuf.flip();
        bytes = new byte[readBuf.remaining()];
        readBuf.get(bytes, 0, bytes.length);
        System.out.print("process(): " + new String(bytes, Charset.forName("ISO-8859-1")));

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(readBuf.array())));
            String request = in.readLine();
            if (request == null || !request.startsWith("GET ") || !(request.endsWith(" HTTP/1.0") || request.endsWith("HTTP/1.1"))) {
                // Invalid request type (no "GET")
                writeBuf.put(GetErrorMessage(500, "Invalid Method.").getBytes());
                return;
            }            
            String path = request.substring(4, request.length() - 9);            
            File file = new File(_rootDir, URLDecoder.decode(path, "UTF-8")).getCanonicalFile();
            
            if (file.isDirectory()) {
                // Check to see if there is an index file in the directory.
                File indexFile = new File(file, "index.html");
                if (indexFile.exists() && !indexFile.isDirectory()) {
                    file = indexFile;
                }
            }

            if (!file.toString().startsWith(_rootDir.toString())) {
                // Uh-oh, it looks like some lamer is trying to take a peek
                // outside of our web root directory.
                writeBuf.put(GetErrorMessage(403, "Permission Denied.").getBytes());
            }
            else if (!file.exists()) {
                // The file was not found.
                writeBuf.put(GetErrorMessage(404, "File Not Found.").getBytes());
            }
            else if (file.isDirectory()) {
                // print directory listing
                if (!path.endsWith("/")) {
                    path = path + "/";
                }
                File[] files = file.listFiles();
                writeBuf.put(GetHTTPHeader(200, "text/html", -1, System.currentTimeMillis()).getBytes());
                String title = "Index of " + path;
                writeBuf.put(("<html><head><title>" + title + "</title></head><body><h3>Index of " + path + "</h3><p>\n").getBytes());
                for (int i = 0; i < files.length; i++) {
                    file = files[i];
                    String filename = file.getName();
                    String description = "";
                    if (file.isDirectory()) {
                        description = "&lt;DIR&gt;";
                    }
                    writeBuf.put(("<a href=\"" + path + filename + "\">" + filename + "</a> " + description + "<br>\n").getBytes());
                    //if(i == 10)
                        //break;
                }
                writeBuf.put(("</p><hr><p>" + VERSION + "</p></body><html>").getBytes());
            }
            else {
                fileSize = file.length();
                reader = new BufferedInputStream(new FileInputStream(file));
            
                String contentType = (String)MIME_TYPES.get(getExtension(file));
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                writeBuf.put(GetHTTPHeader(200, contentType, fileSize, file.lastModified()).getBytes());
                FillBuffer();
            }
        }
        catch (IOException e) {
            if (reader != null) {
                try {
                    reader.close();
                }
                catch (Exception anye) {    
                    // Do nothing.
                }
            }
        }
        // Set the key's interest to WRITE operation
        selKey.interestOps(SelectionKey.OP_WRITE);
        selKey.selector().wakeup();
    }
    //TODO: Create a class with fileBuffer and fileBufferContentSize as its members. We are reusing this buffer. 
    //The content size needs to be tracked.
    void CopyToChannelBuffer(){
        writeBuf.put(fileBuffer, 0, fileBufferContentSize);
        marker += fileBufferContentSize;
        //we are reusing the buffer, so ensure that it is reset once its content has been used.
        Arrays.fill(fileBuffer, (byte)0);
        fileBufferContentSize = 0;        
    }
    
    //Handle the file content transfer.
    //Called initially from the process() function and then from the write function
    //repeatedly until the file has been completely transferred.
    synchronized Boolean FillBuffer(){    
        if(marker < fileSize){            
            try {
                //Handle the case when data was read from file reader but could not be written to fd.
                if(fileBufferContentSize > 0){
                    CopyToChannelBuffer();
                }
                //The size of writeBuf and the fileBuffer is same.
                //Ensure that we do not read more than what we can transfer.
                while ((fileBufferContentSize = reader.read(fileBuffer,0,WRITE_BUF_SIZE-writeBuf.position())) != -1) {
                    CopyToChannelBuffer();
                    //prevents running endlessly without reading any thing.
                    if(writeBuf.position() == WRITE_BUF_SIZE){
                        break;
                    }
                }
                //Close the reader once the file has been transferred completely.
                if(marker == fileSize){
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }
    
    //Called by the NIO framework when a fd is ready to be read from
    synchronized void read() throws IOException {
        int numBytes;

        try {
            numBytes = channel.read(readBuf);
            System.out.println("read(): #bytes read into 'readBuf' buffer = " + numBytes);

            if (numBytes == -1) {
                selKey.cancel();
                channel.close();
                System.out.println("read(): client connection might have been dropped!");
            }
            else {
                Reactor.workerPool.execute(new Runnable() {
                    public void run() {
                        process();
                    }
                });
            }
        }
        catch (IOException ex) {
            ex.printStackTrace();
            return;
        }
    }

    //Called by the NIO framework when a fd is ready to be written to
    synchronized void write() throws IOException {
        int numBytes = 0;
        try {            
            do
            {
                writeBuf.flip();
                while(writeBuf.remaining()>0)
                    numBytes += channel.write(writeBuf);
                writeBuf.clear();
                System.out.println("write(): #bytes read from 'writeBuf' buffer = " + numBytes);
            }
            while(FillBuffer());
            //Close the channel once the request has been serviced completely.
            channel.close();
        }
        catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
