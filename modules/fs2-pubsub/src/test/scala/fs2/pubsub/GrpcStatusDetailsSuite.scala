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

import fs2.pubsub.grpc.internal.GetTopicRequest
import munit.FunSuite
import org.http4s.Header
import org.http4s.grpc.GrpcStatusCode
import org.http4s.grpc.GrpcStatusDetails
import org.http4s.grpc.codecs.NamedHeaders.GrpcStatusDetailsBin

class GrpcStatusDetailsSuite extends FunSuite {

  test("grpc-status-details-bin round-trips a packed ScalaPB message through http4s-grpc") {
    val request = GetTopicRequest.of(topic = "projects/example/topics/example-topic")

    val encoded = Header[GrpcStatusDetailsBin].value(
      GrpcStatusDetailsBin(GrpcStatusDetails(GrpcStatusCode.NotFound, "missing").addDetails(request))
    )

    val decoded = Header[GrpcStatusDetailsBin].parse(encoded).toOption.get.details

    assertEquals(decoded.code, GrpcStatusCode.NotFound)
    assertEquals(decoded.message, "missing")
    assertEquals(decoded.details.map(_.unpack(GetTopicRequest)), List(request))
  }

}
