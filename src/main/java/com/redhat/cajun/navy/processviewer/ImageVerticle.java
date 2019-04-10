package com.redhat.cajun.navy.processviewer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.eventbus.Message;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jbpm.process.svg.SVGImageProcessor;

public class ImageVerticle extends AbstractVerticle {

    @Override
    public Completable rxStart() {

        vertx.eventBus().<JsonObject>consumer("process-image").toObservable()
                .subscribe(this::image);
        vertx.eventBus().<JsonObject>consumer("process-instance-image").toObservable()
                .subscribe(this::instanceImage);
        vertx.eventBus().<JsonObject>consumer("process-instance-data").toObservable()
                .subscribe(this::instanceData);
        return Completable.complete();

    }

    private void image(Message<JsonObject> message) {
        String processId = message.body().getString("processId");
        imageAsString(processId)
                .subscribe((result) -> message.reply(new JsonObject().put("image", result)),
                        (err) -> message.fail(-1, err.getMessage()));

    }

    private void instanceImage(Message<JsonObject> message) {
        String correlationKey = message.body().getString("correlationKey");
        process(correlationKey).flatMap(json ->
                Single.zip(imageAsByteArray(json.getString("processid")),
                        historyActive(json.getLong("processinstanceid")),
                        historyComplete(json.getLong("processinstanceid")),
                        (image, historyActive, historyComplete) -> {
                            Map<String, String> subProcessLinks = new HashMap<>();
                            Map<String, String> active = historyActive.stream().collect(Collectors.toMap(
                                    k -> ((JsonObject) k).getString("nodeinstanceid"),
                                    v -> ((JsonObject) v).getString("nodeid")));
                            List<String> completed = historyComplete.stream().map(o -> {
                                JsonObject json1 = (JsonObject)o;
                                active.remove(json1.getString("nodeinstanceid"));
                                return json1.getString("nodeid");
                            }).collect(Collectors.toList());
                            ByteArrayInputStream svgStream = new ByteArrayInputStream(image);
                            return Triple.of(svgStream, completed, new ArrayList<>(active.values()));
                        })
                .flatMap(t -> processImage(t.getLeft(), t.getMiddle(), t.getRight()))
                .map(s -> s.replaceFirst("<\\?.*\\?>","")))
                .subscribe((result) -> message.reply(new JsonObject().put("image", result)),
                        (err) -> {
                            if (err instanceof ReplyException) {
                                message.fail(((ReplyException)err).failureCode(), err.getMessage());
                            } else {
                                message.fail(-1, err.getMessage());
                            }
                        });
    }

    private void instanceData(Message<JsonObject> message) {
        String correlationKey = message.body().getString("correlationKey");

        process(correlationKey).flatMap(json ->
                Single.zip(Single.just(json), imageAsByteArray(json.getString("processid")),
                        historyActive(json.getLong("processinstanceid")),
                        historyComplete(json.getLong("processinstanceid")),
                        (json1, image, historyActive, historyComplete) -> {
                            Map<String, String> subProcessLinks = new HashMap<>();
                            Map<String, String> active = historyActive.stream().collect(Collectors.toMap(
                                    k -> ((JsonObject) k).getString("nodeinstanceid"),
                                    v -> ((JsonObject) v).getString("nodeid")));
                            List<String> completed = historyComplete.stream().map(o -> {
                                JsonObject json2 = (JsonObject)o;
                                active.remove(json2.getString("nodeinstanceid"));
                                return json2.getString("nodeid");
                            }).collect(Collectors.toList());
                            ByteArrayInputStream svgStream = new ByteArrayInputStream(image);
                            return Pair.of(json1, Triple.of(svgStream, completed, ((List<String>) new ArrayList<>(active.values()))));
                        })
                        .flatMap(p -> processImage(p.getLeft(), p.getRight()))
                        .map(p -> Pair.of(p.getLeft(), p.getRight().replaceFirst("<\\?.*\\?>","")
                            .replaceFirst("width=\"[0-9]*\"", "width=\"1080\"")
                             .replaceFirst("height=\"[0-9]*\"","height=\"auto\""))))
                .subscribe((result) -> message.reply(new JsonObject().put("process", result.getLeft()).put("image", result.getRight())),
                        (err) -> {
                            if (err instanceof ReplyException) {
                                message.fail(((ReplyException)err).failureCode(), err.getMessage());
                            } else {
                                message.fail(-1, err.getMessage());
                            }
                        });
    }

    private Single<String> processImage(ByteArrayInputStream image, List<String> completed, List<String> active) {
        return  vertx.<String>rxExecuteBlocking(future -> future.complete(SVGImageProcessor.transform(image, completed, active, new HashMap<>())))
                .flatMapSingle(Single::just);
    }

    private Single<Pair<JsonObject, String>> processImage(JsonObject json, Triple<ByteArrayInputStream, List<String>, List<String>> input) {
        return  processImage(input.getLeft(), input.getMiddle(), input.getRight())
                .map(s -> Pair.of(json, s));
    }

    private Single<String> imageAsString(String processId) {
        return imageAsByteArray(processId).map(String::new);
    }


    private Single<byte[]> imageAsByteArray(String processId) {
        return vertx.<byte[]>rxExecuteBlocking(future -> {
            try {
                future.complete(imageAsBytes(processId  + "-svg.svg"));
            } catch (IOException e) {
                future.fail(e);
            }
        }).flatMapSingle(Single::just);
    }

    private byte[] imageAsBytes(String location) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(location);
        return IOUtils.toByteArray(is);
    }

    private Single<JsonObject> process(String correlationKey) {
        return query("processInstanceByCorrelationKey", new JsonObject().put("correlationKey", correlationKey))
                .map(a -> a.getJsonObject(0));
    }

    private Single<JsonArray> historyActive(long processInstanceId) {
        return query("processInstanceHistoryActive", new JsonObject().put("processInstanceId", processInstanceId));
    }

    private Single<JsonArray> historyComplete(long processInstanceId) {
        return query("processInstanceHistoryComplete", new JsonObject().put("processInstanceId", processInstanceId));
    }

    private Single<JsonArray> query(String name, JsonObject parameters) {
        JsonObject json = new JsonObject()
                .put("query", name)
                .put("parameters", parameters);
        return vertx.eventBus().<JsonObject>rxSend("query", json)
                .map(m -> m.body().getJsonArray("result"));
    }
}
