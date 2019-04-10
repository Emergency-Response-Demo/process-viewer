package com.redhat.cajun.navy.processviewer;

import java.io.IOException;
import java.io.InputStream;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.eventbus.Message;
import org.apache.commons.io.IOUtils;

public class ImageVerticle extends AbstractVerticle {

    @Override
    public Completable rxStart() {

        vertx.eventBus().<JsonObject>consumer("process-image").toObservable()
                .subscribe(this::image);
        vertx.eventBus().<JsonObject>consumer("process-instance-image").toObservable()
                .subscribe(this::instanceImage);
        return Completable.complete();

    }

    private void image(Message<JsonObject> message) {
        String processId = message.body().getString("processId");
        imageAsString(processId)
                .subscribe((result) -> message.reply(new JsonObject().put("image", result)),
                        (err) -> message.fail(-1, err.getMessage()),
                        () -> message.reply(new JsonObject().put("image", "")));

    }

    private void instanceImage(Message<JsonObject> msg) {
    }

    private Maybe<String> imageAsString(String processId) {
        return vertx.rxExecuteBlocking(future -> {
            try {
                future.complete(new String(imageAsBytes(processId  + "-svg.svg")));
            } catch (IOException e) {
                future.fail(e);
            }
        });
    }

    private byte[] imageAsBytes(String location) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(location);
        return IOUtils.toByteArray(is);
    }
}
