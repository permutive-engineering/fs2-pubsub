/*
 * Copyright 2018 Permutive
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.permutive.pubsub.producer.http

import cats.effect._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import org.http4s.okhttp.client.OkHttpBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._
import scala.util.Try

object ExampleGoogle extends IOApp {

  implicit final val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig)

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val client = OkHttpBuilder
      .withDefaultClient[IO]
      .flatMap(_.resource)

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("fs2-google-pubsub")

    val mkProducer = HttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = Some("/path/to/service/account"),
      config = PubsubHttpProducerConfig(
        host = "pubsub.googleapis.com",
        port = 443,
        oauthTokenRefreshInterval = 30.minutes
      ),
      _
    )

    client
      .flatMap(mkProducer)
      .use { producer =>
        producer.produce(
          data = ExampleObject("70251cf8-5ffb-4c3f-8f2f-40b9bfe4147c", "example.com")
        )
      }
      .flatTap(output => IO(println(output)))
      .as(ExitCode.Success)
  }
}
