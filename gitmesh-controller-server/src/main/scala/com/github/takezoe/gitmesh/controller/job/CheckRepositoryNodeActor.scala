package com.github.takezoe.gitmesh.controller.job

import akka.actor.Actor
import akka.event.Logging
import cats.effect.IO
import com.github.takezoe.gitmesh.controller.api.models._
import com.github.takezoe.gitmesh.controller.data.DataStore
import com.github.takezoe.gitmesh.controller.data.models._
import com.github.takezoe.gitmesh.controller.util.{Config, ControllerLock, RepositoryLock}
import org.http4s.client.Client
import org.http4s.dsl.io._
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.slf4j.LoggerFactory
import io.circe.generic.auto._
import io.circe.jawn.CirceSupportParser
import io.circe.syntax._
import org.http4s.client.dsl.io._

class CheckRepositoryNodeActor(implicit val config: Config, dataStore: DataStore, httpClient: Client[IO]) extends Actor {

  private val log = Logging(context.system, this)


  override def receive = {
    case _ => {
      if(ControllerLock.runForMaster("**master**", config.url, config.deadDetectionPeriod.master)){
        // Check dead nodes
        val timeout = System.currentTimeMillis() - config.deadDetectionPeriod.node

        dataStore.allNodes().foreach { node =>
          if(node.timestamp < timeout){
            log.warning(s"${node.url} is retired.")
            dataStore.removeNode(node.url)
          }
        }

        // Create replica
        val repos = dataStore.allRepositories()

        repos.filter { x => x.nodes.size < config.replica }.foreach { x =>
          x.primaryNode.foreach { primaryNode =>
            createReplicas(primaryNode, x.name, x.timestamp, x.nodes.size)
          }
        }
      }
    }
  }

  private def createReplicas(primaryNode: String, repositoryName: String, timestamp: Long, enabledNodes: Int): Unit = {
    val lackOfReplicas = config.replica - enabledNodes

    (1 to lackOfReplicas).foreach { _ =>
      dataStore.getUrlOfAvailableNode(repositoryName).map { nodeUrl =>
        log.info(s"Create replica of ${repositoryName} at $nodeUrl")

        if(timestamp == InitialRepositoryId){
          log.info("Create empty repository")
          // Repository is empty
          RepositoryLock.execute(repositoryName, "create replica") {
            httpClient.expect[String](PUT(
              Uri.fromString(s"$nodeUrl/api/repos/${repositoryName}/_clone").toTry.get,
              CloneRequest(primaryNode, true).asJson,
              Header("GITMESH-UPDATE-ID", timestamp.toString)
            )).unsafeRunSync()
            // Insert a node record here because cloning an empty repository is proceeded as 1-phase.
            dataStore.insertNodeRepository(nodeUrl, repositoryName, NodeRepositoryStatus.Ready)
          }
        } else {
          log.info("Clone repository")
          // Repository is not empty.
          httpClient.expect[String](PUT(
            Uri.fromString(s"$nodeUrl/api/repos/${repositoryName}/_clone").toTry.get,
            CloneRequest(primaryNode, false).asJson,
            Header("GITMESH-UPDATE-ID", timestamp.toString)
          )).unsafeRunSync()
          // Insert a node record as PREPARING status here, updated to READY later
          dataStore.insertNodeRepository(nodeUrl, repositoryName, NodeRepositoryStatus.Preparing)
        }
      }
    }
  }

}