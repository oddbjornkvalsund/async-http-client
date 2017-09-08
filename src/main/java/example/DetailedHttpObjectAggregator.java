package example;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

public class DetailedHttpObjectAggregator extends HttpObjectAggregator {
    public DetailedHttpObjectAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DefaultHttpResponse) {
            final DefaultHttpResponse defaultHttpResponse = (DefaultHttpResponse) msg;
            final HttpResponse httpResponse = new DefaultHttpResponse(defaultHttpResponse.protocolVersion(), defaultHttpResponse.status());
            ctx.fireChannelRead(httpResponse);
            final HttpHeaders httpHeaders = defaultHttpResponse.headers();
            ctx.fireChannelRead(httpHeaders);
            ctx.flush();
        } else if (msg instanceof LastHttpContent) {
            final LastHttpContent lastHttpContent = (LastHttpContent) msg;
            ctx.fireChannelRead(lastHttpContent.retain());
            ctx.flush();
        } else if (msg instanceof HttpContent) {
            final HttpContent httpContent = (HttpContent) msg;
            ctx.fireChannelRead(httpContent.retain());
            ctx.flush();
        }

        super.channelRead(ctx, msg);
    }
}
