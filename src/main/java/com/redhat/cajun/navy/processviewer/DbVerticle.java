package com.redhat.cajun.navy.processviewer;

import io.reactivex.Completable;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.eventbus.Message;
import io.vertx.reactivex.ext.jdbc.JDBCClient;

public class DbVerticle extends AbstractVerticle {

    private JDBCClient jdbc;

    @Override
    public Completable rxStart() {

        jdbc = JDBCClient.createShared(vertx, config(), "dispatch-service");
        vertx.eventBus().<JsonObject>consumer("query", this::query);
        return Completable.complete();
    }

    private void query(Message<JsonObject> msg) {
    }


}
