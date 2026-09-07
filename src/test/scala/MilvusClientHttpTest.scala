package com.zilliz.spark.connector

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64
import scala.io.Source
import scala.util.Success

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.funsuite.AnyFunSuite

class MilvusClientHttpTest extends AnyFunSuite {

  test("getSegmentInfo uses the Java 8 HTTP path without changing the API") {
    val request = new AtomicReference[(String, String, String)]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      MilvusClient.segmentsUrl,
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val input = Source.fromInputStream(
            exchange.getRequestBody,
            StandardCharsets.UTF_8.name()
          )
          val body =
            try input.mkString
            finally input.close()
          request.set(
            (
              exchange.getRequestMethod,
              exchange.getRequestHeaders.getFirst("Authorization"),
              body
            )
          )

          val response =
            """{"code":0,"data":{"segmentInfos":[{"insertLogs":[{"fieldID":100,"logIDs":[11,12]}],"deltaLogs":[{"logIDs":[13]}]}]}}"""
              .getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(200, response.length.toLong)
          val output = exchange.getResponseBody
          try output.write(response)
          finally output.close()
        }
      }
    )
    server.start()

    try {
      val token = "user:password"
      val client = new MilvusClient(
        MilvusConnectionParams(
          uri = s"http://127.0.0.1:${server.getAddress.getPort}",
          token = token,
          databaseName = "default"
        )
      )

      assert(
        client.getSegmentInfo(10L, 7L) == Success(
          MilvusSegmentLogInfo(
            segmentID = 7L,
            insertLogIDs = Seq("100/11", "100/12"),
            deleteLogIDs = Seq("13")
          )
        )
      )

      val (method, authorization, body) = request.get()
      assert(method == "POST")
      assert(
        authorization ==
          "Basic " + Base64.getEncoder.encodeToString(
            token.getBytes(StandardCharsets.UTF_8)
          )
      )
      assert(body.contains("\"collectionID\":10"))
      assert(body.contains("\"segmentIDs\":[7]"))
    } finally server.stop(0)
  }
}
