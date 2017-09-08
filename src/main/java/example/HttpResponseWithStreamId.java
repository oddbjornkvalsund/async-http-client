package example;

import io.netty.handler.codec.http.HttpResponse;

public class HttpResponseWithStreamId {
    public final HttpResponse httpResponse;
    public final int streamId;

    public HttpResponseWithStreamId(HttpResponse httpResponse, int streamId) {
        this.httpResponse = httpResponse;
        this.streamId = streamId;
    }

    @Override
    public String toString() {
        return "HttpResponseWithStreamId{" +
                "streamId=" + streamId +
                ", httpResponse=" + httpResponse +
                '}';
    }
}
