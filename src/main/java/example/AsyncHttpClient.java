package example;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;
import java.util.Collections;
import java.util.Map;

import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;

public class AsyncHttpClient {

    private final Channel channel;

    public static void main(String[] args) throws InterruptedException {
        final AsyncHttpClient client = new AsyncHttpClient("google.com", 443);
        client.run("GET", "/", Collections.emptyMap());
        Thread.sleep(10000);
    }

    AsyncHttpClient(String host, int port) throws InterruptedException {
        final Bootstrap bootstrap = new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        final Http2FrameAdapter adapter = new Http2FrameAdapter() {

                            @Override
                            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endStream) throws Http2Exception {
                                System.out.println("onHeadersRead(ctx, streamId, headers, padding, endStream)");
                                super.onHeadersRead(ctx, streamId, headers, padding, endStream);
                            }

                            @Override
                            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) throws Http2Exception {
                                System.out.println("onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endStream)");
                                super.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endStream);
                            }

                            @Override
                            public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
                                System.out.println("onDataRead(ctx, streamId, data, padding, endOfStream)");
                                return super.onDataRead(ctx, streamId, data, padding, endOfStream);
                            }
                        };

                        final DefaultHttp2Connection connection = new DefaultHttp2Connection(false);
                        final DefaultHttp2FrameReader frameReader = new DefaultHttp2FrameReader();
                        final DefaultHttp2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
                        final DefaultHttp2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(connection, frameWriter);
                        final DefaultHttp2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, frameReader);
                        final Http2ConnectionHandler connectionHandler = new Http2ConnectionHandlerBuilder()
                                .codec(decoder, encoder)
                                .frameListener(adapter)
                                .build();

                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(createSslHandler(ch));
                        pipeline.addLast(connectionHandler);
                    }
                });

        final ChannelFuture channelFuture = bootstrap.connect(host, port);
        this.channel = channelFuture.sync().channel();
    }

    void run(String method, String path, Map<String, String> headerMap) {
        final DefaultHttp2Headers headers = new DefaultHttp2Headers(true);
        headers.scheme("https");
        headers.method(method);
        headers.path(path);
        for (Map.Entry<String, String> header : headerMap.entrySet()) {
            headers.add(header.getKey(), header.getValue());
        }

        final DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(headers, false);
        try {
            channel.writeAndFlush(frame)
                    .addListener(FIRE_EXCEPTION_ON_FAILURE)
                    .sync();
        } catch (Exception e) {
            throw new RuntimeException(e);
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
                            ApplicationProtocolNames.HTTP_2))
                    .build();
            return sslCtx.newHandler(channel.alloc());
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }
}