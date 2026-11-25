package io.agentplatform.connectors;

import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds the response during transfer, including chunked responses without a content length. */
final class BoundedBody implements BodySubscriber<byte[]> {
    private final BodySubscriber<byte[]> downstream = BodySubscribers.ofByteArray();
    private final long limit;
    private long received;
    private Flow.Subscription upstream;

    BoundedBody(long limit) {
        this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
        return downstream.getBody();
    }

    public void onSubscribe(Flow.Subscription subscription) {
        upstream = subscription;
        downstream.onSubscribe(subscription);
    }

    public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) received += buffer.remaining();
        if (received > limit) {
            upstream.cancel();
            downstream.onError(new IllegalStateException("REST result exceeds limit"));
        } else {
            downstream.onNext(buffers);
        }
    }

    public void onError(Throwable error) {
        downstream.onError(error);
    }

    public void onComplete() {
        downstream.onComplete();
    }
}
