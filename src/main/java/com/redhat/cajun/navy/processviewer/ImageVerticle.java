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
import org.jbpm.process.svg.SVGImageProcessor;

public class ImageVerticle extends AbstractVerticle {

    @Override
    public Completable rxStart() {

        vertx.eventBus().<JsonObject>consumer("process-image").toObservable()
                .subscribe(this::image);
        vertx.eventBus().<JsonObject>consumer("process-instance-image").toObservable()
                .subscribe(m -> instanceData(m, false));
        vertx.eventBus().<JsonObject>consumer("process-instance-data").toObservable()
                .subscribe(m -> instanceData(m, true));
        return Completable.complete();

    }

    private void image(Message<JsonObject> message) {
        String processId = message.body().getString("processId");
        imageAsString(processId)
                .subscribe((result) -> message.reply(new JsonObject().put("image", result)),
                        (err) -> message.fail(-1, err.getMessage()));

    }

    private void instanceData(Message<JsonObject> message, boolean data) {
        String correlationKey = message.body().getString("correlationKey");
        process(correlationKey)
                .compose(json -> data ? json.flatMap(this::addProcessVariables) : json)
                .flatMap(this::addImage)
                .flatMap(this::addHistoryActive)
                .flatMap(this::addHistoryComplete)
                .flatMap(this::processImage)
                .subscribe(message::reply,
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

    private Single<JsonObject> processImage(JsonObject json) {
        Map<String, String> active = json.getJsonArray("historyActive").stream().collect(Collectors.toMap(
                k -> ((JsonObject) k).getString("nodeinstanceid"),
                v -> ((JsonObject) v).getString("nodeid")));
        List<String> completed = json.getJsonArray("historyComplete").stream().map(o -> {
            JsonObject json2 = (JsonObject)o;
            active.remove(json2.getString("nodeinstanceid"));
            return json2.getString("nodeid");
        }).collect(Collectors.toList());
        ByteArrayInputStream svgStream = new ByteArrayInputStream(json.getBinary("imageBytes"));
        return processImage(svgStream, completed, new ArrayList<>(active.values()))
                .map(image -> image.replaceFirst("<\\?.*\\?>","")
                        .replaceFirst("width=\"[0-9]*\"", "width=\"1080\"")
                        .replaceFirst("height=\"[0-9]*\"","height=\"auto\""))
                .map(image -> json.put("image", image))
                .map(j -> {
                    j.remove("imageBytes");
                    j.remove("historyActive");
                    j.remove("historyComplete");
                    return j;
                });
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

    private Single<JsonObject> addProcessVariables(JsonObject processInstance) {

        return processVariables(processInstance.getLong("processinstanceid"))
                .map(variables -> processInstance.put("variables", variables));
    }

    private Single<JsonObject> addHistoryActive(JsonObject processInstance) {
        return historyActive(processInstance.getLong("processinstanceid"))
                .map(historyactive -> processInstance.put("historyActive", historyactive));
    }

    private Single<JsonObject> addHistoryComplete(JsonObject processInstance) {
        return historyComplete(processInstance.getLong("processinstanceid"))
                .map(historycomplete -> processInstance.put("historyComplete", historycomplete));
    }

    private Single<JsonObject> addImage(JsonObject processInstance) {
        return imageAsByteArray(processInstance.getString("processid"))
                .map(b -> processInstance.put("imageBytes", b));
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

    private Single<JsonArray> processVariables(long processInstanceId) {
        return query("processInstanceVariableValues", new JsonObject().put("processInstanceId", processInstanceId));
    }

    private Single<JsonArray> query(String name, JsonObject parameters) {
        JsonObject json = new JsonObject()
                .put("query", name)
                .put("parameters", parameters);
        return vertx.eventBus().<JsonObject>rxRequest("query", json)
                .map(m -> m.body().getJsonArray("result"));
    }
}
