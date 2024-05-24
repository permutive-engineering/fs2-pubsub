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

package com.permutive.pubsub.producer.grpc

import cats.effect.{ExitCode, IO, IOApp}
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder

import scala.concurrent.duration._
import scala.annotation.nowarn

@nowarn("cat=deprecation")
object PubsubProducerExample extends IOApp {

  case class Value(v: Int) extends AnyVal

  implicit val encoder: MessageEncoder[Value] = new MessageEncoder[Value] {
    override def encode(a: Value): Either[Throwable, Array[Byte]] =
      Right(BigInt(a.v).toByteArray)
  }

  override def run(args: List[String]): IO[ExitCode] =
    GooglePubsubProducer
      .of[IO, Value](
        Model.ProjectId("test-project"),
        Model.Topic("values"),
        config = PubsubProducerConfig[IO](
          batchSize = 100,
          delayThreshold = 100.millis,
          onFailedTerminate = e => IO(println(s"Got error $e")) >> IO.unit
        )
      )
      .use { producer =>
        producer.produce(
          Value(10)
        )
      }
      .map(_ => ExitCode.Success)
}
