package com.redhat.cajun.navy.processviewer;

import io.reactivex.Completable;
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
                .flatMap(ctx -> engine.rxRender(ctx, "templates/process-image.ftl"))
                .subscribe((result) -> rc.response().setStatusCode(200)
                                        .putHeader("content-type", "text/html")
                                        .end(result),
                        rc::fail);
    }
}
