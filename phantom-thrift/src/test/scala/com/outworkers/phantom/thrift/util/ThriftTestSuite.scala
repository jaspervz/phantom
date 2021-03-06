/*
 * Copyright 2013 - 2019 Outworkers Ltd.
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
package com.outworkers.phantom.thrift.util

import java.util.UUID

import com.outworkers.phantom.PhantomSuite
import com.outworkers.phantom.thrift.tests.ThriftRecord
import com.outworkers.util.samplers._
import com.twitter.util.{Future, Return, Throw}

trait ThriftTestSuite extends PhantomSuite {

  implicit def twitterFutureToConcept[T](f: Future[T]): FutureConcept[T] = new FutureConcept[T] {
    override def eitherValue: Option[Either[Throwable, T]] = f.poll match {
      case Some(Return(ret)) => Some(Right(ret))
      case Some(Throw(err)) => Some(Left(err))
      case None => None
    }

    override def isExpired: Boolean = false

    override def isCanceled: Boolean = false
  }

  type ThriftTest = com.outworkers.phantom.thrift.models.ThriftTest
  val ThriftTest = com.outworkers.phantom.thrift.models.ThriftTest

  implicit object ThriftTestSample extends Sample[ThriftTest] {
    def sample: ThriftTest = ThriftTest(
      gen[Int],
      gen[String],
      test = false
    )
  }

  implicit object OutputSample extends Sample[ThriftRecord] {
    def sample: ThriftRecord = {
      ThriftRecord(
        id = gen[UUID],
        name = gen[String],
        struct = gen[ThriftTest],
        thriftSet = genList[ThriftTest]().toSet[ThriftTest],
        thriftList = genList[ThriftTest](),
        thriftMap = genList[ThriftTest]().map {
          item => (item.toString, item)
        }.toMap,
        optThrift = genOpt[ThriftTest]
      )
    }
  }

}
