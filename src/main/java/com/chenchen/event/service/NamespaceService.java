package com.chenchen.event.service;

import com.chenchen.event.dto.NamespaceCreateDTO;
import com.chenchen.event.namespace.NamespaceManager;
import com.chenchen.event.verticle.NamespaceVerticle;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamespaceService {
  private static final Logger logger= LoggerFactory.getLogger(NamespaceService.class);

  private NamespaceService(){}

  @Data
  private static class NamespaceCreate {
    private NamespaceCreateDTO dto;
    private String verticleId;
  }

  private static void createNamespaceSuccess(RoutingContext req, String id) {
    logger.info("Create namespace verticle success, id {}", id);
    req.response().end(id);
  }

  private static void createNamespaceFail(RoutingContext req, String message) {
    logger.info("Create namespace verticle fail : {}", message);
    req.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code())
      .putHeader("content-type", "text/plain")
      .end("Create namespace verticle fail : " + message);
  }

  public static void create(RoutingContext req, Vertx vertx) {

    // trans to pojo
    NamespaceCreateDTO namespaceCreateDTO = req.body().asPojo(NamespaceCreateDTO.class);
    logger.info("Receive create namespace [{}]", namespaceCreateDTO);

    // trans
    Future<NamespaceCreate> future = Future.succeededFuture(namespaceCreateDTO).compose(dto -> {
      NamespaceCreate create = new NamespaceCreate();
      create.setDto(dto);
      return Future.succeededFuture(create);
    });

    // check namespace available
    Future<NamespaceCreate> checkNameSpaceFuture = future.compose(create -> {
      String ns = create.getDto().getNamespace();
      return vertx.executeBlocking(promise -> {
        if (NamespaceManager.preCreateNamespace(ns)) {
          logger.info("Namespace [{}] is available", ns);
          promise.complete(create);
        } else {
          logger.info("Namespace [{}] has been created", ns);
          promise.fail("Namespace [" + ns + "] has been created");
        }
      });
    });

    // check success, create namespace
    Future<NamespaceCreate> createNamespaceFuture = checkNameSpaceFuture.compose(create -> {
      String ns = create.getDto().getNamespace();
      // start namespace vertical
      DeploymentOptions deploymentOptions = new DeploymentOptions()
        .setWorker(true)
        .setInstances(1)
        .setWorkerPoolSize(1)
        .setConfig(new JsonObject().put(NamespaceManager.NAMESPACE, ns))
        .setWorkerPoolName("namespace-thread-" + ns);

      logger.info("Start namespace [{}] verticle", ns);
      return vertx.deployVerticle(NamespaceVerticle.class, deploymentOptions).compose(success -> {
        create.setVerticleId(success);
        return Future.succeededFuture(create);
      });
    });

    // create success, update namespace manager
    Future<NamespaceCreate> updateNamespaceManagerFuture = createNamespaceFuture.compose(create -> {
      String ns = create.getDto().getNamespace();
      String id = create.getVerticleId();
      logger.info("Success to create namespace [{}] verticle id [{}]", ns, id);
      return vertx.executeBlocking(promise -> {
        if (NamespaceManager.updateNamespace(ns, id)) {
          logger.info("Namespace [{}] update namespace manager success id [{}]", ns, id);
          promise.complete(create);
        } else {
          logger.info("Namespace [{}] update namespace manager fail id [{}]", ns, id);
          // should be roll back
          logger.info("Roll back create namespace [{}] verticle id [{}]", ns, id);
          Future<Void> rollBackFuture = vertx.undeploy(id);
          rollBackFuture.onSuccess(r -> {
            logger.info("Roll back verticle id [{}] success", r);
            promise.fail("Namespace [" + ns + "] update namespace manager fail id [ " + r + "] and roll back success");
          });
          rollBackFuture.onFailure(r -> {
            logger.warn("Roll back verticle id [{}] fail : {}", id, r.getMessage());
            promise.fail("Namespace [" + ns + "] update namespace manager fail id [ " + id + "] and roll back fail : " + r.getMessage());
          });
        }
      });
    });

    // update success
    updateNamespaceManagerFuture.onSuccess(create -> createNamespaceSuccess(req, create.getVerticleId()));

    // update fail
    updateNamespaceManagerFuture.onFailure(fail -> createNamespaceFail(req, fail.getMessage()));
  }
}
