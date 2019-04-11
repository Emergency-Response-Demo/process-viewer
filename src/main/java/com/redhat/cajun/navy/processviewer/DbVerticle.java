package com.redhat.cajun.navy.processviewer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.eventbus.Message;
import io.vertx.reactivex.ext.jdbc.JDBCClient;
import io.vertx.reactivex.ext.sql.SQLConnection;

public class DbVerticle extends AbstractVerticle {

    private JDBCClient jdbcClient;

    @Override
    public Completable rxStart() {

        jdbcClient = JDBCClient.createShared(vertx, config(), "dispatch-service");
        vertx.eventBus().consumer("query", this::query);
        return Completable.complete();
    }

    private void query(Message<JsonObject> message) {
        switch (message.body().getString("query")) {
            case "processInstanceByCorrelationKey":
                connect()
                        .flatMap(connection -> processInstanceByCorrelationKey(connection,
                                message.body().getJsonObject("parameters").getString("correlationKey")))
                        .map(json -> new JsonArray().add(json))
                        .subscribe((result) -> message.reply(new JsonObject().put("result", result)),
                                (err) -> {
                                    if (err instanceof NoSuchElementException) {
                                        message.fail(1, err.getMessage());
                                    } else {
                                        message.fail(-1, err.getMessage());
                                    }
                                });
                break;
            case "processInstanceHistoryActive":
                connect()
                        .flatMap(connection -> processInstanceHistoryActive(connection,
                                message.body().getJsonObject("parameters").getLong("processInstanceId")))
                        .subscribe((result) -> message.reply(new JsonObject().put("result", new JsonArray(result))),
                                (err) -> message.fail(-1, err.getMessage()));
                break;
            case "processInstanceHistoryComplete":
                connect()
                        .flatMap(connection -> processInstanceHistoryComplete(connection,
                                message.body().getJsonObject("parameters").getLong("processInstanceId")))
                        .subscribe((result) -> message.reply(new JsonObject().put("result", new JsonArray(result))),
                                (err) -> message.fail(-1, err.getMessage()));
                break;
            case "processInstanceVariableValues":
                connect()
                        .flatMap(connection -> processInstanceVariableValues(connection,
                                message.body().getJsonObject("parameters").getLong("processInstanceId")))
                        .subscribe((result) -> message.reply(new JsonObject().put("result", new JsonArray(result))),
                                (err) -> message.fail(-1, err.getMessage()));
                break;
            default:
                break;
        }
    }

    private SingleSource<JsonObject> processInstanceByCorrelationKey(SQLConnection connection, String correlationKey) {
        String sql = "SELECT * FROM processinstancelog WHERE correlationkey = ?";
        return connection.rxQueryWithParams(sql, new JsonArray().add(correlationKey))
                .doFinally(connection::close)
                .map(rs -> {
                    List<JsonObject> rows = rs.getRows();
                    if (rows.size() == 0) {
                        throw new NoSuchElementException("Process instance with correlation key " + correlationKey + " not found.");
                    } else {
                        return rows.get(0);
                    }
                });
    }

    private Single<List<JsonObject>> processInstanceHistoryActive(SQLConnection connection, long processInstanceId) {
        String sql = "SELECT nodeinstanceid, nodeid, nodename, nodetype, externalid, processinstanceid, " +
                "log_date, connection, type, workitemid, nodecontainerid, referenceid, sla_due_date, slacompliance " +
                "FROM nodeinstancelog " +
                "WHERE nodeinstanceid in ( SELECT nil.nodeinstanceid from public.nodeinstancelog nil " +
                "WHERE nil.processinstanceid = ? " +
                "GROUP BY nil.nodeinstanceid " +
                "HAVING sum(nil.type) = 0) " +
                "AND type = 0 " +
                "AND processinstanceid = ? " +
                "ORDER BY id ASC";


        return connection.rxQueryWithParams(sql, new JsonArray().add(processInstanceId).add(processInstanceId))
                .doFinally(connection::close)
                .map(rs -> {
                    if (rs.getRows() == null) {
                        return new ArrayList<>();
                    } else {
                        return rs.getRows();
                    }
                });
    }

    private Single<List<JsonObject>> processInstanceHistoryComplete(SQLConnection connection, long processInstanceId) {
        String sql = "SELECT nodeinstanceid, nodeid, nodename, nodetype, externalid, processinstanceid, " +
                "log_date, connection, type, workitemid, nodecontainerid, referenceid, sla_due_date, slacompliance " +
                "FROM nodeinstancelog " +
                "WHERE nodeinstanceid in ( SELECT nil.nodeinstanceid from nodeinstancelog nil " +
                "WHERE nil.processinstanceid = ? " +
                "AND nil.type = 1 " +
                "GROUP BY nil.nodeinstanceid " +
                "HAVING sum(nil.type) >= 1) " +
                "AND type = 1 " +
                "AND processinstanceid = ? " +
                "ORDER BY id ASC";

        return connection.rxQueryWithParams(sql, new JsonArray().add(processInstanceId).add(processInstanceId))
                .doFinally(connection::close)
                .map(rs -> {
                    if (rs.getRows() == null) {
                        return new ArrayList<>();
                    } else {
                        return rs.getRows();
                    }
                });
    }

    private Single<List<JsonObject>> processInstanceVariableValues(SQLConnection connection, long processInstanceId) {
        String sql = "SELECT id, log_date, v1.processinstanceid, value, v1.variableid, variableinstanceid " +
                "FROM public.variableinstancelog v1 " +
                "INNER JOIN ( " +
                "SELECT variableid, MAX(log_date) as mts, processinstanceid " +
                "FROM public.variableinstancelog " +
                "GROUP BY variableid, processinstanceid " +
                ") v2 on v1.variableid = v2.variableid and v1.processinstanceid = v2.processinstanceid and v1.log_date = v2.mts " +
                "WHERE v1.processinstanceid = ?;";

        return connection.rxQueryWithParams(sql, new JsonArray().add(processInstanceId))
                .doFinally(connection::close)
                .map(rs-> {
                    if (rs.getRows() == null) {
                        return Collections.emptyList();
                    } else {
                        return rs.getRows();
                    }
                });
    }

    private Single<SQLConnection> connect() {
        return jdbcClient.rxGetConnection();
    }

}
