package com.redhat.cajun.navy.processviewer;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.CompletableHelper;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.AbstractVerticle;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Future<Void> startFuture) throws Exception {

        ConfigStoreOptions localStore = new ConfigStoreOptions().setType("file").setFormat("yaml")
                .setConfig(new JsonObject()
                        .put("path", System.getProperty("vertx-config-path")));
        ConfigStoreOptions configMapStore = new ConfigStoreOptions()
                .setType("configmap")
                .setFormat("yaml")
                .setConfig(new JsonObject()
                        .put("name", System.getProperty("application.configmap", "process-viewer"))
                        .put("key", System.getProperty("application.configmap.key", "application-config.yaml")));
        ConfigRetrieverOptions options = new ConfigRetrieverOptions();
        if (System.getenv("KUBERNETES_NAMESPACE") != null) {
            //we're running in Kubernetes
            options.addStore(configMapStore);
        } else {
            //default to local config
            options.addStore(localStore);
        }

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
        retriever.rxGetConfig()
                .flatMapCompletable(json -> {
                    JsonObject datasource = json.getJsonObject("datasource");
                    JsonObject http = json.getJsonObject("http");
                    return vertx.rxDeployVerticle(RestApiVerticle.class.getName(), new DeploymentOptions().setConfig(http)).ignoreElement()
                            .andThen(vertx.rxDeployVerticle(DbVerticle.class.getName(), new DeploymentOptions().setConfig(datasource))).ignoreElement()
                            .andThen(vertx.rxDeployVerticle(ImageVerticle.class.getName())).ignoreElement();
                })
                .subscribe(CompletableHelper.toObserver(startFuture));
    }
}
