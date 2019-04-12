package com.redhat.cajun.navy.processviewer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Completable;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.Status;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.ext.healthchecks.HealthCheckHandler;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.templ.freemarker.FreeMarkerTemplateEngine;

public class RestApiVerticle extends AbstractVerticle {

    FreeMarkerTemplateEngine engine;

    @Override
    public Completable rxStart() {
        return initializeHttpServer(config());
    }

    private Completable initializeHttpServer(JsonObject config) {

        engine = FreeMarkerTemplateEngine.create(vertx);

        Router router = Router.router(vertx);

        HealthCheckHandler healthCheckHandler = HealthCheckHandler.create(vertx)
                .register("health", f -> f.complete(Status.OK()));
        router.get("/health").handler(healthCheckHandler);
        router.get("/image/process/:processId").handler(this::processImage);
        router.get("/image/process/instance/:correlationKey").handler(this::processInstanceImage);
        router.get("/data/process/instance/:correlationKey").handler(this::processInstanceData);

        return vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(config.getInteger("port", 8080))
                .ignoreElement();
    }

    private void processImage(RoutingContext rc) {
        String processId = rc.pathParam("processId");
        JsonObject json = new JsonObject().put("processId", processId);
        vertx.eventBus().<JsonObject>rxSend("process-image", json)
                .map(m -> new JsonObject().put("image", m.body().getString("image")))
                .subscribe((result) -> rc.response().setStatusCode(200)
                                        .putHeader("content-type", "image/svg+xml")
                                        .end(result.getString("image")),
                        rc::fail);
    }

    private void processInstanceImage(RoutingContext rc) {
        String correlationKey = rc.pathParam("correlationKey");
        JsonObject json = new JsonObject().put("correlationKey", correlationKey);
        vertx.eventBus().<JsonObject>rxSend("process-instance-image", json)
                .map(m -> new JsonObject().put("image", m.body().getString("image")))
                .subscribe((result) -> rc.response().setStatusCode(200)
                                        .putHeader("content-type", "image/svg+xml")
                                        .end(result.getString("image")),
                        (err) -> {
                            if (err instanceof ReplyException && ((ReplyException)err).failureCode() == 1) {
                                rc.response().setStatusCode(404).end();
                            } else {
                                rc.fail(err);
                            }
                        });
    }

    private void processInstanceData(RoutingContext rc) {
        String correlationKey = rc.pathParam("correlationKey");
        JsonObject json = new JsonObject().put("correlationKey", correlationKey);
        vertx.eventBus().<JsonObject>rxSend("process-instance-data", json)
                .map(m -> transformProcessData(m.body()))
                .flatMap(ctx -> engine.rxRender(ctx, "templates/process-data.ftl"))
                .subscribe((result) -> rc.response().setStatusCode(200)
                                .putHeader("content-type", "text/html")
                                .end(result),
                        (err) -> {
                            if (err instanceof ReplyException && ((ReplyException)err).failureCode() == 1) {
                                rc.response().setStatusCode(404).end();
                            } else {
                                err.printStackTrace();
                                rc.fail(err);
                            }
                        });
    }

    private JsonObject transformProcessData(JsonObject data) {

        DateTimeFormatter in = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        DateTimeFormatter out = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        String mission = getVariableValue(data.getJsonArray("variables"), "mission");
        String incident = getVariableValue(data.getJsonArray("variables"), "incident");

        return new JsonObject()
                .put("correlationKey", data.getString("correlationkey"))
                .put("instanceId", Long.toString(data.getLong("processinstanceid")))
                .put("processId", data.getString("processid"))
                .put("status", status(data.getInteger("status")))
                .put("startDate", out.format(LocalDateTime.from(in.parse(data.getString("start_date")))))
                .put("endDate", data.getString("end_date") == null ? "" : out.format(LocalDateTime.from(in.parse(data.getString("end_date")))))
                .put("duration", data.getLong("duration") == null ? "" : data.getLong("duration") / 1000)
                .put("assignments_retries", getVariableValue(data.getJsonArray("variables"), "nrAssignments"))
                .put("responder_id", match(".*responderId=([0-9]*),", mission))
                .put("incident_location", coordinates(".*latitude=([-+]?[0-9.]*),",".*longitude=([-+]?[0-9.]*),", incident))
                .put("responder_location",coordinates(".*responderLat=([-+]?[0-9.]*),",".*responderLong=([-+]?[0-9.]*),", mission))
                .put("destination_location",coordinates(".*destinationLat=([-+]?[0-9.]*),",".*destinationLong=([-+]?[0-9.]*),", mission))
                .put("image", data.getString("image"));


        //responderId=87
    }

    private String status(int status) {
        switch (status) {
            case 0:
                return "Pending";
            case 1:
                return "Active";
            case 2:
                return "Completed";
            case 3:
                return "Aborted";
            case 4:
                return "Suspended";
            default:
                return "Unknown Status";

        }
    }

    private String getVariableValue(JsonArray array, String id) {

        return Optional.ofNullable(array)
                .orElse(new JsonArray())
                .stream().filter(o -> o instanceof JsonObject)
                .map(o -> (JsonObject)o)
                .filter(o -> id.equals(o.getString("variableid")))
                .map(j -> Optional.ofNullable(j.getString("value")))
                .findFirst()
                .orElse(Optional.of(""))
                .orElse("");
    }

    private String match(String pattern, String text) {
        Pattern p = Pattern.compile(pattern);
        Matcher matcher = p.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "";
        }
    }

    private String coordinates(String patternLat, String patternLon, String text) {
        String lat = match(patternLat, text);
        String lon = match(patternLon, text);
        if (lat.isEmpty() || lon.isEmpty()) {
            return "";
        } else {
            return lat + "," + lon;
        }
    }



}
