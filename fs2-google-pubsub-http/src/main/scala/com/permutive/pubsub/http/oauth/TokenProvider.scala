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

package com.permutive.pubsub.http.oauth

import scala.util.control.NoStackTrace

trait TokenProvider[F[_]] {
  def accessToken: F[AccessToken]
}

object TokenProvider {
  case object TokenValidityTooLong
      extends RuntimeException("Valid for duration cannot be longer than maximum of the OAuth provider")
      with NoStackTrace

  case object FailedToGetToken extends RuntimeException("Failed to get token after many attempts")

  def instance[F[_]](token: F[AccessToken]): TokenProvider[F] = new TokenProvider[F] {
    override val accessToken: F[AccessToken] = token
  }
}
