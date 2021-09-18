package com.butterfly.nioserver;

import com.butterfly.nioserver.ButterflySoftCache.CacheEntry;
import com.butterfly.nioserver.RequestHeaderHandler.Verb;
import com.butterfly.nioserver.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.butterfly.nioserver.HttpResponseHeaderBuilder.*;

public class RequestHandler implements Runnable {

    private static final DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    static {
        formatter.setTimeZone(TimeZone.getDefault());
    }

    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);
    private final ButterflySoftCache cache;
    private final List<RequestSegmentHeader> pendingRequestSegment = new ArrayList<>();
    private final Map<SocketChannel, RequestHeaderHandler> requestMap = new WeakHashMap<>();
    private final NioHttpServer server;
    private final String serverRoot;

    /**
     * @param server  {@link NioHttpServer} the server
     * @param wwwRoot wwwRoot
     * @param cache   cache implementation
     */
    public RequestHandler(NioHttpServer server, String wwwRoot, ButterflySoftCache cache) {
        this.cache = cache;
        this.serverRoot = wwwRoot;
        this.server = server;
    }

    public void processData(SocketChannel client, byte[] data, int count) {

        byte[] dataCopy = new byte[count];
        System.arraycopy(data, 0, dataCopy, 0, count);

        synchronized (pendingRequestSegment) {
            // add data
            pendingRequestSegment.add(new RequestSegmentHeader(client, dataCopy));
            pendingRequestSegment.notify();
        }
    }

    @Override
    public void run() {

        RequestSegmentHeader requestData = null;
        RequestHeaderHandler header = null;
        CacheEntry entry = null;
        HttpResponseHeaderBuilder builder = new HttpResponseHeaderBuilder();
        byte[] head = null;
        byte[] body = null;
        String file = null;
        String mime = null;
        boolean zip = false;

        // wait for data
        while (true) {

            synchronized (pendingRequestSegment) {
                while (pendingRequestSegment.isEmpty()) {
                    try {
                        pendingRequestSegment.wait();
                    } catch (InterruptedException e) {
                    }
                }
                requestData = pendingRequestSegment.remove(0);
            }

            header = requestMap.get(requestData.client);
            if (header == null) {
                header = new RequestHeaderHandler();
                requestMap.put(requestData.client, header);
            }
            try {
                if (header.appendSegment(requestData.data)) {
                    file = serverRoot + header.getResouce();
                    File currentFile = new File(file);
                    mime = new MimetypesFileTypeMap().getContentType(currentFile);
                    logger.info(currentFile + "\t" + mime);
                    String acceptEncoding = header.getHeader(ACCEPT_ENCODING);
                    // gzip text
                    zip = mime.contains("text") && acceptEncoding != null && acceptEncoding.contains("gzip");
                    if (zip) {
                        entry = cache.get(file + GZIP);
                    } else {
                        entry = cache.get(file);
                    }

                    // miss the cache
                    if (entry == null) {
                        builder.clear(); // get ready for next request;

                        logger.info("miss the cache " + file);

                        // always keep alive
                        builder.addHeader(CONNECTION, KEEP_ALIVE);
                        builder.addHeader(CONTENT_TYPE, mime);

                        // response body byte, exception throws here
                        body = Utils.file2ByteArray(currentFile, zip);
                        builder.addHeader(CONTENT_LENGTH, body.length);
                        if (zip) {
                            // add zip header
                            builder.addHeader(CONTENT_ENCODING, GZIP);
                        }

                        // last modified header
                        Date lastModified = new Date(currentFile.lastModified());
                        builder.addHeader(LAST_MODIFIED, formatter.format(lastModified));

                        // response header byte
                        head = builder.getHeader();
                        // add to the cache
                        if (zip)
                            file = file + GZIP;
                        cache.put(file, head, body);
                    } else {
                        // cache is hit
                        if (logger.isDebugEnabled())
                            logger.debug("cache is hit" + file);
                        body = entry.body;
                        head = entry.header;
                    }
                    // data is prepared, send out to the client
                    server.send(requestData.client, head);
                    if (body != null && header.getVerb() == Verb.GET)
                        server.send(requestData.client, body);
                }
            } catch (IOException e) {
                builder.addHeader(CONTENT_LENGTH, 0);
                builder.setStatus(NOT_FOUND_404);
                head = builder.getHeader();
                server.send(requestData.client, head);
                // cache 404 if case client make a mistake again
                cache.put(file, head, body);
                logger.error("404 error", e);

            } catch (Exception e) {
                // any other, it's a 505 error
                builder.addHeader(CONTENT_LENGTH, 0);
                builder.setStatus(SERVER_ERROR_500);
                head = builder.getHeader();
                server.send(requestData.client, head);
                logger.error("505 error", e);
            }
        }
    }

    private static class RequestSegmentHeader {
        SocketChannel client;
        byte[] data;

        public RequestSegmentHeader(SocketChannel client, byte[] data) {
            this.client = client;
            this.data = data;
        }
    }
}
