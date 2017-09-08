package example;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Promise;

import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;
import static io.netty.handler.logging.LogLevel.INFO;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class AsyncHttpClient implements Closeable {

    enum Version {
        HTTP_1_1,
        HTTP_2
    }

    private final static AsciiString schemeHeaderName = HttpConversionUtil.ExtensionHeaderNames.SCHEME.text();
    private final static AsciiString streamIdHeaderName = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text();

    private final NioEventLoopGroup group;
    private final Channel channel;
    private final Version version;
    private final String host;
    private final boolean useSSL;

    private AtomicInteger streamIdCounter = new AtomicInteger(3);

    public static void main(String[] args) throws InterruptedException, IOException {
        final byte[] emptyBody = new byte[0];
        for (Version version : Version.values()) {
            try (final AsyncHttpClient client = new AsyncHttpClient(version, "google.com", 443, true, true)) {
                final Promise<FullHttpResponse> future1 = client.run("GET", "/", emptyMap(), emptyBody,
                        response -> {
                            System.out.format("Got %s response for GET /: %s\n", version, response);
                        },
                        (headers, isLast) -> {
                            System.out.format("Got %s headers for GET /: %s\n", version, headers);
                            System.out.format("Got %s headers.isLast for GET /: %s\n", version, isLast);
                        },
                        (content, isLast) -> {
                            System.out.format("Got %s content for GET /: %s\n", version, content);
                            System.out.format("Got %s content.isLast for GET /: %s\n", version, isLast);
                        }
                );

                final Promise<FullHttpResponse> future2 = client.run("GET", "/foo", emptyMap(), emptyBody,
                        response -> {
                            System.out.format("Got %s response for GET /foo: %s\n", version, response);
                        },
                        (headers, isLast) -> {
                            System.out.format("Got %s headers for GET /foo: %s\n", version, headers);
                            System.out.format("Got %s headers.isLast for GET /foo: %s\n", version, isLast);
                        },
                        (content, isLast) -> {
                            System.out.format("Got %s content for GET /foo: %s\n", version, content);
                            System.out.format("Got %s content.isLast for GET /foo: %s\n", version, isLast);
                        }
                );

                final Promise<FullHttpResponse> result1 = future1.sync();
                System.out.format("Got %s full content for GET /: %s\n", version, result1.getNow());

                final Promise<FullHttpResponse> result2 = future2.sync();
                System.out.format("Got %s full content for GET /foo: %s\n", version, result2.getNow());
            }
        }
    }

    public AsyncHttpClient(Version version, String host, int port, boolean useSSL, boolean decompressBody) {
        this.version = version;
        this.host = host;
        this.useSSL = useSSL;
        this.group = new NioEventLoopGroup();

        if (version == Version.HTTP_2 && !useSSL) {
            throw new IllegalArgumentException("HTTP/2 only supported over SSL!");
        }

        final Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        final ChannelPipeline pipeline = ch.pipeline();

                        if (useSSL) {
                            pipeline.addFirst(createSslHandler(ch));
                        }

                        if (version == Version.HTTP_1_1) {
                            pipeline.addLast(new HttpClientCodec());
                            pipeline.addLast(new DetailedHttpObjectAggregator(10 * 1024 * 1024));
                            if (decompressBody) {
                                pipeline.addLast(new HttpContentDecompressor());
                            }
                        } else if (version == Version.HTTP_2) {
                            final Http2FrameLogger frameLogger = new Http2FrameLogger(INFO, AsyncHttpClient.class);
                            final DefaultHttp2Connection connection = new DefaultHttp2Connection(false);
                            final InboundHttp2ToHttpAdapter adapter = new DetailedInboundHttp2ToHttpAdapter(connection);
                            final HttpToHttp2ConnectionHandler connectionHandler = new HttpToHttp2ConnectionHandlerBuilder()
                                    .frameListener(decompressBody ? new DelegatingDecompressorFrameListener(connection, adapter) : adapter)
                                    .frameLogger(frameLogger)
                                    .connection(connection)
                                    .build();

                            pipeline.addLast(connectionHandler);
                        }
                    }
                });

        final ChannelFuture channelFuture = bootstrap.connect(host, port);
        try {
            this.channel = channelFuture.sync().channel();
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
    }

    private Promise<FullHttpResponse> run(String method, String path, Map<String, String> headerMap, byte[] body) {
        return run(method, path, headerMap, body,
                r -> {
                },
                (h, b) -> {
                },
                (c, b) -> {
                }
        );
    }

    private Promise<FullHttpResponse> run(String method, String path, Map<String, String> headerMap, byte[] body, Consumer<HttpResponse> onResponse, BiConsumer<HttpHeaders, Boolean> onHeaders, BiConsumer<HttpContent, Boolean> onContent) {
        final DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), path, Unpooled.wrappedBuffer(body));
        final HttpHeaders headers = req.headers();
        headers.add(HttpHeaderNames.HOST, this.host);
        headers.add(HttpHeaderNames.CONTENT_LENGTH, body.length);
        headers.add(schemeHeaderName, useSSL ? "https" : "http");

        for (Map.Entry<String, String> header : headerMap.entrySet()) {
            headers.add(header.getKey(), header.getValue());
        }

        final Promise<FullHttpResponse> promise = channel.pipeline().firstContext().executor().newPromise();

        if (version == Version.HTTP_1_1) {
            channel.pipeline().addLast(new Http1ResponseHandler(onResponse, onHeaders, onContent, promise));
            channel.writeAndFlush(req).addListener(FIRE_EXCEPTION_ON_FAILURE).syncUninterruptibly();
            return promise.syncUninterruptibly();
        } else {
            channel.pipeline().addLast(new Http2ResponseHandler(getNextStreamId(), onResponse, onHeaders, onContent, promise));
            channel.writeAndFlush(req).addListener(FIRE_EXCEPTION_ON_FAILURE);
            return promise;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close().sync();
            group.shutdownGracefully(0, 100, MILLISECONDS);
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while closing channel!", e);
        }
    }

    class Http1ResponseHandler extends SimpleChannelInboundHandler<HttpObject> {

        private final Consumer<HttpResponse> onResponse;
        private final BiConsumer<HttpHeaders, Boolean> onHeaders;
        private final BiConsumer<HttpContent, Boolean> onContent;
        private final Promise<FullHttpResponse> promise;

        Http1ResponseHandler(Consumer<HttpResponse> onResponse, BiConsumer<HttpHeaders, Boolean> onHeaders, BiConsumer<HttpContent, Boolean> onContent, Promise<FullHttpResponse> promise) {
            this.onResponse = onResponse;
            this.onHeaders = onHeaders;
            this.onContent = onContent;
            this.promise = promise;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            if (msg instanceof FullHttpResponse) {
                final FullHttpResponse fullHttpResponse = (FullHttpResponse) msg;
                channel.pipeline().remove(this);
                promise.setSuccess(fullHttpResponse); // retain?
            } else if (msg instanceof HttpResponse) {
                final HttpResponse httpResponse = (HttpResponse) msg;
                onResponse.accept(httpResponse);
            } else if (msg instanceof HttpHeaders) {
                final HttpHeaders httpHeaders = (HttpHeaders) msg;
                onHeaders.accept(httpHeaders, true);
            } else if (msg instanceof LastHttpContent) {
                final LastHttpContent lastHttpContent = (LastHttpContent) msg;
                onContent.accept(lastHttpContent, true);
            } else if (msg instanceof HttpContent) {
                final HttpContent httpContent = (HttpContent) msg;
                onContent.accept(httpContent, false);
            }
        }
    }

    class Http2ResponseHandler extends SimpleChannelInboundHandler<Object> {

        private final int streamId;
        private final Consumer<HttpResponse> onResponse;
        private final BiConsumer<HttpHeaders, Boolean> onHeaders;
        private final BiConsumer<HttpContent, Boolean> onContent;
        private final Promise<FullHttpResponse> promise;

        Http2ResponseHandler(int streamId, Consumer<HttpResponse> onResponse, BiConsumer<HttpHeaders, Boolean> onHeaders, BiConsumer<HttpContent, Boolean> onContent, Promise<FullHttpResponse> promise) {
            this.streamId = streamId;
            this.onResponse = onResponse;
            this.onHeaders = onHeaders;
            this.onContent = onContent;
            this.promise = promise;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpResponseWithStreamId) {
                final HttpResponseWithStreamId objectWithStreamId = (HttpResponseWithStreamId) msg;
                if (objectWithStreamId.streamId == streamId) {
                    onResponse.accept(objectWithStreamId.httpResponse);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof HttpHeadersWithStreamId) {
                final HttpHeadersWithStreamId objectWithStreamId = (HttpHeadersWithStreamId) msg;
                if (objectWithStreamId.streamId == streamId) {
                    onHeaders.accept(objectWithStreamId.httpHeaders, objectWithStreamId.noMoreContent);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof HttpContentWithStreamId) {
                final HttpContentWithStreamId objectWithStreamId = (HttpContentWithStreamId) msg;
                if (objectWithStreamId.streamId == streamId) {
                    onContent.accept(objectWithStreamId.httpContent, objectWithStreamId.noMoreContent);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof FullHttpResponse) {
                final FullHttpResponse fullHttpResponse = (FullHttpResponse) msg;
                final int responseStreamId = fullHttpResponse.headers().getInt(streamIdHeaderName);
                if (responseStreamId == streamId) {
                    promise.trySuccess(fullHttpResponse);
                } else {
                    ctx.fireChannelRead(fullHttpResponse.retain());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            promise.tryFailure(cause);
        }
    }

    private SslHandler createSslHandler(SocketChannel channel) {
        try {
            final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
            final SslContext sslCtx = SslContextBuilder.forClient().sslProvider(provider)
            /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
            * Please refer to the HTTP/2 specification for cipher requirements. */
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            version == Version.HTTP_2 ? ApplicationProtocolNames.HTTP_2 : ApplicationProtocolNames.HTTP_1_1))
                    .build();
            return sslCtx.newHandler(channel.alloc());
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    private int getNextStreamId() {
        return streamIdCounter.getAndAdd(2);
    }
}