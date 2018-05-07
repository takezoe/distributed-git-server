package com.github.takezoe.gitmesh.repository.api

import java.io.File

import com.github.takezoe.gitmesh.repository.util._
import com.github.takezoe.resty._
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class APIController(implicit val config: Config) extends HttpClientSupport with GitOperations {

  private val log = LoggerFactory.getLogger(classOf[APIController])

  implicit override val httpClientConfig = config.httpClient

  private def getRepositories(): Seq[String] = {
    val rootDir = new File(config.directory)
    rootDir.listFiles(_.isDirectory).toSeq.map(_.getName)
  }

  @Action(method = "GET", path = "/")
  def status(): Status = {
    val rootDir = new File(config.directory)
    val diskUsage = rootDir.getFreeSpace.toDouble / rootDir.getTotalSpace.toDouble
    val repos = getRepositories()

    Status(
      url = config.url,
      diskUsage = diskUsage,
      repos = repos
    )
  }

  @Action(method = "POST", path = "/api/repos/{repositoryName}")
  def createRepository(repositoryName: String,
                       @Param(from = "header", name = "GITMESH-UPDATE-ID") timestamp: Long): ActionResult[Unit] = {
    // Delete the repository directory if it exists
    val dir = new File(config.directory, repositoryName)
    if(dir.exists){
      FileUtils.forceDelete(dir)
    }

    log.info(s"Create repository: $repositoryName")

    // git init
    gitInit(repositoryName)

    // Write timestamp
    val file = new File(config.directory, s"$repositoryName.id")
    FileUtils.write(file, timestamp.toString, "UTF-8")

    Ok((): Unit)
  }

  @Action(method = "GET", path = "/api/repos")
  def listRepositories(): Seq[Repository] = {
    val rootDir = new File(config.directory)
    rootDir.listFiles(_.isDirectory).toSeq.map { dir =>
      Repository(dir.getName, gitCheckEmpty(dir.getName))
    }
  }

  @Action(method = "GET", path = "/api/repos/{repositoryName}")
  def showRepositoryStatus(repositoryName: String): ActionResult[Repository] = {
    val dir = new File(config.directory, repositoryName)
    if(dir.exists()){
      Ok(Repository(repositoryName, gitCheckEmpty(repositoryName)))
    } else {
      NotFound()
    }
  }

  @Action(method="DELETE", path = "/api/repos/{repositoryName}")
  def deleteRepository(repositoryName: String): Unit = {
    log.info(s"Delete repository: $repositoryName")

    val dir = new File(config.directory, repositoryName)
    if(dir.exists){
      FileUtils.forceDelete(dir)
    }

    val file = new File(config.directory, s"$repositoryName.id")
    if(file.exists){
      FileUtils.forceDelete(file)
    }
  }

  @Action(method = "PUT", path = "/api/repos/{repositoryName}/_clone")
  def cloneRepository(repositoryName: String, request: CloneRequest,
                      @Param(from = "header", name = "GITMESH-UPDATE-ID") timestamp: Long): Unit = {
    Future {
      val remoteUrl = s"${config.url}/git/$repositoryName.git"
      log.info(s"Clone repository: $repositoryName from ${remoteUrl}")

      // Delete the repository directory if it exists
      val dir = new File(config.directory, repositoryName)
      if(dir.exists){
        FileUtils.forceDelete(dir)
      }

      if(request.empty){
        log.info("Create empty repository")

        // Create an empty repository
        gitInit(repositoryName)

        // write timestamp
        val file = new File(config.directory, s"$repositoryName.id")
        FileUtils.write(file, timestamp.toString, "UTF-8")

      } else {
        log.info("Clone repository")
        // Clone the remote repository (without lock)
        gitClone(repositoryName, remoteUrl)

        // write timestamp
        // TODO This should be done after 2nd-phase (push from primary repo) in fact.
        val file = new File(config.directory, s"$repositoryName.id")
        FileUtils.write(file, timestamp.toString, "UTF-8")

        // Request second phase to the primary node
        httpPutJson(
          s"${request.nodeUrl}/api/repos/$repositoryName/_sync",
          SynchronizeRequest(config.url),
          builder => { builder.addHeader("GITMESH-UPDATE-ID", timestamp.toString) }
        )
      }
    }

    ()
  }

  @Action(method = "PUT", path = "/api/repos/{repositoryName}/_sync")
  def synchronizeRepository(repositoryName: String, request: SynchronizeRequest,
                            @Param(from = "header", name = "GITMESH-UPDATE-ID") timestamp: Long): Unit = {

    Future {
      val remoteUrl = s"${request.nodeUrl}/git/$repositoryName.git"
      log.info(s"Synchronize repository: $repositoryName with ${remoteUrl}")

      // Push all to the remote repository (with lock)
      httpPost(
        config.controllerUrl.map { controllerUrl =>
          s"${controllerUrl}/api/repos/$repositoryName/_lock"
        },
        Map.empty
      )

      gitPushAll(repositoryName, remoteUrl)

      httpPostJson(
        config.controllerUrl.map { controllerUrl =>
          s"${controllerUrl}/api/repos/$repositoryName/_synced"
        },
        SynchronizedRequest(request.nodeUrl)
      )
    }

    ()
  }

}