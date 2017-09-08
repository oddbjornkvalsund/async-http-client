package example;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Promise;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

class Http2ResponseHandler extends SimpleChannelInboundHandler<Object> {

    private final static AsciiString streamIdHeaderName = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text();

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
