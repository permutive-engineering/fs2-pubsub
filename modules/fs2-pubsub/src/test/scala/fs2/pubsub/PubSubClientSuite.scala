/*
 * Copyright 2019-2026 Permutive Ltd. <https://permutive.com>
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

package fs2.pubsub

import cats.effect.IO

import munit.FunSuite
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.syntax.all._

class PubSubClientSuite extends FunSuite {

  test("PubSubClient can be created from configuration class") {
    val config = PubSubClient.Config(
      projectId = ProjectId.random(),
      uri = uri"localhost:8080"
    )

    val client: Client[IO] = Client.fromHttpApp(HttpApp.notFound[IO])

    val publisher = PubSubClient
      .http[IO]
      .fromConfig(config)
      .httpClient(client)
      .noRetry

    assert(publisher.isInstanceOf[PubSubClient[IO]])
  }

}
