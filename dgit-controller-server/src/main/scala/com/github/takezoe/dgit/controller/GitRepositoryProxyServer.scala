package com.github.takezoe.dgit.controller

import java.io.{File, FileInputStream, FileOutputStream}
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import scala.collection.JavaConverters._
import okhttp3.{MediaType, OkHttpClient, Request, RequestBody}
import org.apache.commons.io.IOUtils
import Utils._

class GitRepositoryProxyServer extends HttpServlet {

  private val client = new OkHttpClient()

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    println("** doPost **")
    val path = req.getRequestURI
    val queryString = req.getQueryString
    val repositoryName = path.replaceAll("(^/git/)|(\\.git($|/.*))", "")
    val nodes = Nodes.selectNodes(repositoryName)

    if(nodes.nonEmpty){
      val tmpFile = File.createTempFile("dgit", "tmpfile")
      using(req.getInputStream, new FileOutputStream(tmpFile)){ (in, out) =>
        IOUtils.copy(in, out)
      }

      nodes.zipWithIndex.foreach { case (endpoint, i) =>
        val builder = new Request.Builder().url(endpoint + path + (if(queryString == null) "" else "?" + queryString))

        req.getHeaderNames.asScala.foreach { name =>
          builder.addHeader(name, req.getHeader(name))
        }

        builder.post(RequestBody.create(MediaType.parse(req.getContentType), tmpFile))

        val request = builder.build()
        val response = client.newCall(request).execute()

        if(i == 0){
          response.headers().names().asScala.foreach { name =>
            resp.setHeader(name, response.header(name))
          }
          using(response.body().byteStream(), resp.getOutputStream){ (in, out) =>
            IOUtils.copy(in, out)
          }
        }
      }
    } else {
      // TODO NotFound
    }
  }

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    println("** doGet **")
    val path = req.getRequestURI
    val queryString = req.getQueryString
    val repositoryName = path.replaceAll("(^/git/)|(\\.git($|/.*))", "")

    Nodes.selectNode(repositoryName).map { endpoint =>
      val builder = new Request.Builder().url(endpoint + path + (if(queryString == null) "" else "?" + queryString))

      req.getHeaderNames.asScala.foreach { name =>
        builder.addHeader(name, req.getHeader(name))
      }

      val request = builder.build()
      val response = client.newCall(request).execute()

      response.headers().names().asScala.foreach { name =>
        resp.setHeader(name, response.header(name))
      }

      using(response.body().byteStream(), resp.getOutputStream){ (in, out) =>
        IOUtils.copy(in, out)
      }

    }.getOrElse {
      // TODO NotFound
    }
  }

}
