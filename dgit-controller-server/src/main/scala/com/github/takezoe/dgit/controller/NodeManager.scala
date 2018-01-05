package com.github.takezoe.dgit.controller

import java.util.concurrent.ConcurrentHashMap

import com.github.takezoe.resty.HttpClientSupport

import scala.collection.JavaConverters._
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer

// TODO Should be a class?
object NodeManager extends HttpClientSupport {

  private val log = LoggerFactory.getLogger(getClass)
  private val nodes = new ConcurrentHashMap[String, NodeStatus]()
  private val primaryNodeOfRepository = new ConcurrentHashMap[String, String]()

  def updateNodeStatus(nodeUrl: String, diskUsage: Double, repos: Seq[String]): Unit = {
    val isNew = !nodes.containsKey(nodeUrl)

    val primaryRepositoryNames = new ListBuffer[String]
    repos.foreach { repositoryName =>
      primaryNodeOfRepository.computeIfAbsent(repositoryName, _ => {
        log.info(s"Set $nodeUrl as the primary node for $repositoryName")
        primaryRepositoryNames += repositoryName
        nodeUrl
      })
    }

    if(isNew){
      repos.filterNot(primaryRepositoryNames.contains).foreach { repositoryName =>
        httpDelete[String](s"$nodeUrl/api/repos/$repositoryName")
      }
      nodes.put(nodeUrl, NodeStatus(System.currentTimeMillis(), diskUsage, primaryRepositoryNames))
      log.info(s"Added a repository node: $nodeUrl")

    } else {
      nodes.put(nodeUrl, NodeStatus(System.currentTimeMillis(), diskUsage, repos))
    }
  }

  def removeNode(nodeUrl: String): Unit = {
    nodes.remove(nodeUrl)

    primaryNodeOfRepository.forEach { case (repositoryName, primaryNodeUrl) =>
      if(nodeUrl == primaryNodeUrl){
        nodes.asScala.find { case (_, status) => status.repos.contains(repositoryName) } match {
          case Some((newNode, _)) => {
            // Update the primary node
            primaryNodeOfRepository.put(repositoryName, newNode)
          }
          case None => {
            log.error(s"All nodes for $repositoryName has been retired.")
            primaryNodeOfRepository.remove(repositoryName)
          }
        }
      }
    }
  }

  def allNodes(): Seq[(String, NodeStatus)] = {
    nodes.asScala.toSeq
  }

  def findNode(url: String): Option[(String, NodeStatus)] = {
    nodes.asScala.find { case (nodeUrl, _) => nodeUrl == url }
  }

  def selectNode(repositoryName: String): Option[String] = {
    Option(primaryNodeOfRepository.get(repositoryName))
  }

  def selectNodes(repositoryName: String): Seq[String] = {
    nodes.asScala.collect { case (nodeUrl, status) if status.repos.contains(repositoryName) => nodeUrl }.toSeq
  }

  def selectAvailableNode(repositoryName: String): Option[String] = {
    nodes.asScala.collectFirst { case (node, status) if !status.repos.contains(repositoryName) => node }
  }

  def allRepositories(): Seq[Repository] = {
    nodes.asScala.toSeq
      .flatMap { case (nodeUrl, status) => status.repos.map { repositoryName => (repositoryName, nodeUrl) } }
      .groupBy { case (repositoryName, _) => repositoryName }
      .map { case (repositoryName, group) =>
        Repository(
          repositoryName,
          selectNode(repositoryName).get, // TODO Don't use Option.get!
          group.map { case (_, nodeUrl) => nodeUrl }
        )
      }
      .toSeq
  }

}

case class Repository(name: String, primaryNodeUrl: String, nodes: Seq[String])
case class NodeStatus(timestamp: Long, diskUsage: Double, repos: Seq[String])